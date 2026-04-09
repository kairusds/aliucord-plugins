/*
 * SyncFav, an Aliucord plugin that backports the sync feature of Favorite Emojis from modern Discord clients.
 * Copyright (C) 2025  kairusds
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package github.kairusds.syncfav

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.Base64
import android.widget.TextView
import android.view.View
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.GsonUtils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.aliucord.views.TextInput
import com.aliucord.widgets.BottomSheet
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.stores.StoreMediaFavorites
import com.discord.stores.StoreStream
import com.discord.views.CheckedSetting
import com.aliucord.utils.DimenUtils
import com.aliucord.views.Button
import com.discord.widgets.emoji.WidgetEmojiSheet
import java.io.ByteArrayOutputStream
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@AliucordPlugin
class SyncFav : Plugin(){
	private val log = Logger("SyncFav")
	private val scheduler = Executors.newSingleThreadScheduledExecutor()
	private var syncFuture: ScheduledFuture<*>? = null

	companion object {
		lateinit var instance: SyncFav
	}

	init {
		instance = this
		settingsTab = SettingsTab(Settings::class.java, SettingsTab.Type.PAGE).withArgs(settings)
	}

	data class CachedEmoji(val id: String, val name: String, val isCustom: Boolean)

	override fun start(context: Context){
		scheduler.execute { FavStore.fetchRemote(log) }
		scheduleSync()
		patchMediaFavorites()
		patchWidgetEmojiSheet()
	}

	override fun stop(context: Context){
		patcher.unpatchAll()
		syncFuture?.cancel(true)
		scheduler.shutdownNow()
	}

	fun scheduleSync(){
		syncFuture?.cancel(true)
		if(!settings.getBool("periodic_sync_enabled", true)) return
		val delay = settings.getLong("sync_delay", 60L)		
		syncFuture = scheduler.scheduleWithFixedDelay({
			try{
				FavStore.fetchRemote(log)
			}catch(e: Throwable){
				log.error("Background sync failed", e)
			}
		}, delay, delay, TimeUnit.SECONDS)
		log.info("Periodic sync scheduled every $delay seconds.")
	}

	object FavStore{
		val emojis = LinkedHashSet<CachedEmoji>()
		var updateSubject: Any? = null
		var lastFetchedData: ByteArray? = null
		var hasFetched = false
		
		init{
			try{
				val subjectClass = Class.forName("rx.subjects.BehaviorSubject")
				val method = subjectClass.declaredMethods.firstOrNull{ 
					java.lang.reflect.Modifier.isStatic(it.modifiers) &&
					it.parameterTypes.size == 1 &&
					it.returnType == subjectClass
				}
				if(method != null){
					updateSubject = method.invoke(null, true)
				}
			}catch(e: Throwable){}
		}

		fun triggerUpdate(){
			if(updateSubject != null){
				try{
					val method = updateSubject!!.javaClass.getMethod("onNext", java.lang.Object::class.java)
					method.invoke(updateSubject, true)
				}catch(e: Throwable){}
			}
		}

		private const val SETTINGS_PROTO_ROUTE = "/users/@me/settings-proto/2"
		data class ProtoResponse(val settings: String?)

		fun fetchRemote(log: Logger){
			try{
				val response = Http.Request.newDiscordRNRequest(SETTINGS_PROTO_ROUTE)
					.execute()
					.json(ProtoResponse::class.java)

				if(response.settings != null){
					val rawProto = Base64.decode(response.settings, Base64.DEFAULT)
					log.info("[FavStore] Fetched ${rawProto.size} bytes.")
					lastFetchedData = rawProto
					parseProto(rawProto, log)
					hasFetched = true
					triggerUpdate()
				}
			}catch(e: Throwable){
				log.error("Failed to fetch favorites", e)
			}
		}

		fun pushRemote(log: Logger){
			if(!hasFetched) {
				log.warn("Push aborted to prevent data loss: initial fetch hasn't completed.")
				return
			}
			try{
				val newBytes = LightProto.createFavoritesPayload(lastFetchedData, emojis)
				val base64Str = Base64.encodeToString(newBytes, Base64.NO_WRAP)
				val body = ProtoResponse(base64Str)

				Http.Request.newDiscordRNRequest(SETTINGS_PROTO_ROUTE, "PATCH")
					.executeWithJson(body)

				log.info("[FavStore] Pushed favorites update (${newBytes.size} bytes)")
				lastFetchedData = newBytes
				triggerUpdate()
			}catch(e: Throwable){
				log.error("Failed to push favorites", e)
			}
		}

		private fun parseProto(bytes: ByteArray, log: Logger){
			val result = LightProto.extractFavorites(bytes)
			synchronized(this){
				emojis.clear()
				val emojiStore = StoreStream.getEmojis()
				
				for(idStr in result){
					try{
						val longId = idStr.toLongOrNull()
						if(longId != null && longId > 0){
							val custom = emojiStore.getCustomEmojiInternal(longId)
							if(custom != null){
								emojis.add(CachedEmoji(idStr, custom.name, true))
							}else{
								emojis.add(CachedEmoji(idStr, "unknown", true))
							}
						}else{
							emojis.add(CachedEmoji(idStr, idStr, false))
						}
					}catch(ex: Exception){}
				}
			}
		}
	}

	object LightProto{
		private const val FIELD_FAV_EMOJIS_WRAPPER = 5
		private const val FIELD_EMOJI_ID = 1

		fun extractFavorites(data: ByteArray): List<String>{
			val emojiIds = ArrayList<String>()
			try{
				val reader = ProtoReader(data)
				while(reader.hasRemaining()){
					val (tag, type) = reader.readTag()
					
					if(tag == FIELD_FAV_EMOJIS_WRAPPER && type == 2){
						val innerBytes = reader.readBytes()
						val innerReader = ProtoReader(innerBytes)
						while(innerReader.hasRemaining()){
							val (innerTag, innerType) = innerReader.readTag()
							if(innerTag == FIELD_EMOJI_ID && innerType == 2){
								emojiIds.add(String(innerReader.readBytes()))
							}else{
								innerReader.skipField(innerType)
							}
						}
					}else{
						reader.skipField(type)
					}
				}
			}catch(e: Exception){ e.printStackTrace() }
			return emojiIds
		}

		fun createFavoritesPayload(original: ByteArray?, emojis: Set<CachedEmoji>): ByteArray{
			val rootOut = ByteArrayOutputStream()
			var innerOriginalBytes = ByteArray(0)

			if(original != null && original.isNotEmpty()){
				val reader = ProtoReader(original)
				while(reader.hasRemaining()){
					val startPos = reader.pos
					val (tag, type) = reader.readTag()
					if(tag == 0) break 
					
					if(tag == FIELD_FAV_EMOJIS_WRAPPER && type == 2){
						innerOriginalBytes = reader.readBytes()
					}else{
						reader.skipField(type)
						rootOut.write(original, startPos, reader.pos - startPos)
					}
				}
			}

			val innerOut = ByteArrayOutputStream()

			if(innerOriginalBytes.isNotEmpty()){
				val innerReader = ProtoReader(innerOriginalBytes)
				while (innerReader.hasRemaining()){
					val startPos = innerReader.pos
					val (tag, type) = innerReader.readTag()
					if(tag == 0) break

					if(tag == FIELD_EMOJI_ID && type == 2){
						innerReader.skipField(type) 
					}else{
						innerReader.skipField(type)
						innerOut.write(innerOriginalBytes, startPos, innerReader.pos - startPos)
					}
				}
			}

			for(e in emojis){
				writeStringField(innerOut, FIELD_EMOJI_ID, e.id)
			}

			val innerBytes = innerOut.toByteArray()
			writeLengthDelimited(rootOut, FIELD_FAV_EMOJIS_WRAPPER, innerBytes)

			return rootOut.toByteArray()
		}

		private fun writeStringField(out: ByteArrayOutputStream, field: Int, value: String){
			val tag = (field shl 3) or 2
			writeVarInt(out, tag.toLong())
			val bytes = value.toByteArray()
			writeVarInt(out, bytes.size.toLong())
			out.write(bytes)
		}

		private fun writeLengthDelimited(out: ByteArrayOutputStream, field: Int, bytes: ByteArray){
			val tag = (field shl 3) or 2
			writeVarInt(out, tag.toLong())
			writeVarInt(out, bytes.size.toLong())
			out.write(bytes)
		}

		private fun writeVarInt(out: ByteArrayOutputStream, value: Long){
			var v = value
			while((v and 0x7FL.inv()) != 0L){
				out.write(((v.toInt() and 0x7F) or 0x80))
				v = v ushr 7
			}
			out.write(v.toInt() and 0x7F)
		}

		class ProtoReader(private val data: ByteArray){
			var pos = 0
			fun hasRemaining() = pos < data.size
			
			fun readTag(): Pair<Int, Int>{
				if(pos >= data.size) return 0 to 0
				val tagWithType = readVarInt()
				val tag = (tagWithType ushr 3).toInt()
				val type = (tagWithType and 7).toInt()
				if(tag == 0 && type == 0 && tagWithType == 0L) pos = data.size
				return tag to type
			}

			fun readVarInt(): Long{
				var value = 0L
				var shift = 0
				while(pos < data.size){
					val b = data[pos++].toInt()
					value = value or ((b.toLong() and 0x7F) shl shift)
					if((b and 0x80) == 0) return value
					shift += 7
				}
				return value
			}

			fun readBytes(): ByteArray{
				val len = readVarInt().toInt()
				if(len < 0 || pos + len > data.size) return ByteArray(0)
				val bytes = ByteArray(len)
				System.arraycopy(data, pos, bytes, 0, len)
				pos += len
				return bytes
			}
			
			fun skipField(type: Int){
				if(type == 0) readVarInt()
				else if(type == 1) pos += 8
				else if(type == 2){
					val len = readVarInt().toInt()
					if(len >= 0) pos += len
				}
				else if(type == 5) pos += 4
			}
		}
	}

	private fun patchMediaFavorites(){
		try{
			val favoritesStore = StoreMediaFavorites::class.java

			val mergeFavorites = { originalInput: Any? ->
				val originalSet = (originalInput as? Set<Any>) ?: emptySet()
				val savedEmojis = FavStore.emojis
				val newSet = LinkedHashSet<Any>()
				val addedIds = HashSet<String>()

				for(localEmoji in savedEmojis){
					try{
						var discordEmoji = resolveRealEmoji(localEmoji.id, localEmoji.name, localEmoji.isCustom)

						if(discordEmoji == null){
							val idL = localEmoji.id.toLongOrNull() ?: 0L
							discordEmoji = if(localEmoji.isCustom && idL != 0L){
								createCustomEmoji(idL, localEmoji.name, false)
							}else{
								createUnicodeEmoji(localEmoji.name)
							}
						}

						if(discordEmoji != null){
							val favObj = createFavoriteInstance(discordEmoji)
							if(favObj != null){
								val idKey = if(localEmoji.isCustom) localEmoji.id else localEmoji.name
								if(addedIds.add(idKey)){
									newSet.add(favObj)
								}
							}
						}
					}catch(e: Throwable){}
				}

				for(existing in originalSet){
					try{
						val existingEmoji = getEmojiFromFavorite(existing)
						if(existingEmoji != null){
							val eId = getEmojiId(existingEmoji)
							val eName = getEmojiName(existingEmoji)
							val isCustom = existingEmoji is ModelEmojiCustom
							val idKey = if(isCustom || eId > 0L) eId.toString() else eName

							if(addedIds.add(idKey)){
								newSet.add(existing)
							}
						}else{
							newSet.add(existing)
						}
					}catch(e: Throwable){
						newSet.add(existing)
					}
				}
				newSet
			}

			patcher.patch(favoritesStore, "getFavorites", arrayOf(Set::class.java), Hook{ callFrame ->
				callFrame.result = mergeFavorites(callFrame.result)
			})

			patcher.patch(favoritesStore, "observeFavorites", arrayOf(Set::class.java), Hook{ callFrame ->
				val originalObservable = callFrame.result
				val triggerObservable = FavStore.updateSubject
				
				if(originalObservable != null && triggerObservable != null){
					callFrame.result = combineLatest(originalObservable, triggerObservable){ originalSet, _ ->
						mergeFavorites(originalSet)
					}
				}
			})

			val favoriteClass = Class.forName("com.discord.stores.StoreMediaFavorites\$Favorite")
			patcher.patch(favoritesStore, "addFavorite", arrayOf(favoriteClass), PreHook{ callFrame ->
				try{
					val favObj = callFrame.args[0]
					val emoji = getEmojiFromFavorite(favObj)
					if(emoji != null){
						addSyncedEmoji(emoji)
						scheduler.execute { FavStore.pushRemote(log) }
					}
				}catch(e: Throwable){
					log.error("Error in addFavorite hook", e)
				}
			})

			patcher.patch(favoritesStore, "removeFavorite", arrayOf(favoriteClass), PreHook{ callFrame ->
				try{
					val favObj = callFrame.args[0]
					val emoji = getEmojiFromFavorite(favObj)
					if(emoji != null){
						removeSyncedEmoji(emoji)
						scheduler.execute { FavStore.pushRemote(log) }
					}
				}catch(e: Throwable){
					log.error("Error in removeFavorite hook", e)
				}
			})

		}catch(e: Throwable){
			log.error("Error patching StoreMediaFavorites", e)
		}
	}

	private fun patchWidgetEmojiSheet(){
		try{
			val widgetClass = WidgetEmojiSheet::class.java
			val method = widgetClass.declaredMethods.firstOrNull{ it.name == "configureFavorite" }
			if(method == null) return
			method.isAccessible = true

			patcher.patch(method, PreHook{ callFrame ->
				val fragment = callFrame.thisObject
				val getEmojiMethod = widgetClass.getDeclaredMethod("getEmojiIdAndType")
				getEmojiMethod.isAccessible = true
				val emojiIdAndType = getEmojiMethod.invoke(fragment) ?: return@PreHook

				val isCustom = try{ ReflectUtils.getField(emojiIdAndType, "isCustom") as Boolean }catch(e: Exception){ true }
				val id = try{ ReflectUtils.getField(emojiIdAndType, "id") as Long }catch(e: Exception){ 0L }
				val name = try{ ReflectUtils.getField(emojiIdAndType, "name") as String }catch(e: Exception){ "emoji" }

				val isLocallyFav = FavStore.emojis.any{
					if(isCustom) it.id == id.toString() else it.id == name
				}

				if(isLocallyFav){
					callFrame.args[0] = true
				}
			})
		}catch(e: Throwable){
			log.error("Failed to patch WidgetEmojiSheet", e)
		}
	}

	private fun addSyncedEmoji(emoji: Emoji){
		val name = getEmojiName(emoji)
		val isCustom = emoji is ModelEmojiCustom

		val idStr = if(isCustom) getEmojiId(emoji).toString() 
			else getUnicodeShortcode(emoji as ModelEmojiUnicode)
		
		synchronized(FavStore){
			if(FavStore.emojis.add(CachedEmoji(idStr, name, isCustom))){
				FavStore.triggerUpdate()
			}
		}
	}

	private fun removeSyncedEmoji(emoji: Emoji){
		val isCustom = emoji is ModelEmojiCustom
		val idStr = if(isCustom) getEmojiId(emoji).toString() 
			else getUnicodeShortcode(emoji as ModelEmojiUnicode)

		synchronized(FavStore){
			if(FavStore.emojis.removeAll{ it.id == idStr }){
				FavStore.triggerUpdate()
			}
		}
	}

	private fun mapObservable(observable: Any, transform: (Any) -> Any): Any{
		try{
			val observableClass = observable.javaClass
			var mapMethod: java.lang.reflect.Method? = null
			var func1Class: Class<*>? = null
			var currentClass: Class<*>? = observableClass
			
			while(currentClass != null){
				val candidates = currentClass.declaredMethods.filter{
					(it.name == "map" || it.name == "G") && it.parameterTypes.size == 1
				}
				if(candidates.isNotEmpty()){
					mapMethod = candidates[0]
					func1Class = mapMethod!!.parameterTypes[0]
					break
				}
				currentClass = currentClass.superclass
			}

			if(mapMethod == null || func1Class == null) return observable

			val proxy = java.lang.reflect.Proxy.newProxyInstance(func1Class.classLoader, arrayOf(func1Class)){ _, _, args ->
				if(args != null && args.isNotEmpty()) return@newProxyInstance transform(args[0])
				return@newProxyInstance null
			}
			return mapMethod.invoke(observable, proxy)
		}catch(e: Throwable){ return observable }
	}

	private fun combineLatest(obs1: Any, obs2: Any, callback: (Any, Any) -> Any): Any {
		try {
			val obsClass = Class.forName("rx.Observable")
			val func2Class = Class.forName("rx.functions.Func2")
			val combineMethod = obsClass.declaredMethods.firstOrNull {
				java.lang.reflect.Modifier.isStatic(it.modifiers) &&
				it.parameterTypes.size == 3 &&
				it.parameterTypes[0] == obsClass &&
				it.parameterTypes[1] == obsClass &&
				it.parameterTypes[2] == func2Class
			}

			if(combineMethod == null) return obs1

			val proxy = java.lang.reflect.Proxy.newProxyInstance(func2Class.classLoader, arrayOf(func2Class)) { _, _, args ->
				if(args != null && args.size == 2){
					return@newProxyInstance callback(args[0], args[1])
				}
				return@newProxyInstance null
			}

			return combineMethod.invoke(null, obs1, obs2, proxy) ?: obs1
		}catch(e: Throwable){
			return obs1
		}
	}

	private fun <T> deserialize(json: String, clazz: Class<T>): T? = with(GsonUtils){ gsonRestApi.fromJson(json, clazz) }

	private fun createCustomEmoji(id: Long, name: String, animated: Boolean): Emoji?{
		val json = "{\"id\":$id,\"name\":\"$name\",\"animated\":$animated,\"available\":true}"
		val emoji = deserialize(json, ModelEmojiCustom::class.java)
		if(emoji != null){
			try{ ReflectUtils.setField(emoji, "isUsable", true) }catch(e: Throwable){}
		}
		return emoji
	}

	private fun createUnicodeEmoji(name: String): Emoji?{
		val safeName = name.replace("\"", "\\\"")
		val json = "{\"names\":[\"$safeName\"],\"surrogates\":\"$safeName\"}"
		return deserialize(json, ModelEmojiUnicode::class.java)
	}

	private fun getUnicodeShortcode(emoji: ModelEmojiUnicode): String {
		try {
			val names = emoji.names
			if(names != null && names.isNotEmpty()){
				return names[0]
			}
		}catch(e: Throwable){
			try {
				val names = ReflectUtils.getField(emoji, "names") as? List<String>
				if (names != null && names.isNotEmpty()) return names[0]
			}catch(e2: Throwable){}
		}
		return emoji.surrogates
	}

	private fun resolveRealEmoji(idStr: String, name: String, custom: Boolean): Emoji?{
		return try{
			val emojiStore = StoreStream.getEmojis()
			if(custom){
				val id = idStr.toLongOrNull() ?: 0L
				if(id > 0) emojiStore.getCustomEmojiInternal(id) else null
			}else{
				val map = emojiStore.unicodeEmojiSurrogateMap
				var emoji = map[idStr]

				if(emoji == null){
					emoji = map.values.firstOrNull { 
						it.names?.contains(idStr) == true || it.names?.contains(name) == true
					}
				}
				emoji
			}
		}catch(e: Throwable){ null }
	}

	private val favCustomClass by lazy{ Class.forName("com.discord.stores.StoreMediaFavorites\$Favorite\$FavCustomEmoji") }
	private val favUnicodeClass by lazy{ Class.forName("com.discord.stores.StoreMediaFavorites\$Favorite\$FavUnicodeEmoji") }

	private fun createFavoriteInstance(emoji: Emoji): Any?{
		if(emoji is ModelEmojiCustom){
			val ctor = favCustomClass.constructors.firstOrNull{ it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
			return ctor?.newInstance(emoji.uniqueId)
		}else if(emoji is ModelEmojiUnicode){
			val ctor = favUnicodeClass.constructors.firstOrNull{ it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(ModelEmojiUnicode::class.java) }
			return ctor?.newInstance(emoji)
		}
		return null
	}

	private fun getEmojiFromFavorite(favObj: Any): Emoji?{
		return try{
			val method = favObj.javaClass.getMethod("getEmojiUniqueId")
			val id = method.invoke(favObj) as? String
			if(id != null){
				val store = StoreStream.getEmojis()
				val longId = id.toLongOrNull()

				if(longId != null && longId > 0){
					return store.getCustomEmojiInternal(longId)
				}

				val realUnicode = store.unicodeEmojiSurrogateMap[id]
				if(realUnicode != null) return realUnicode

				return createUnicodeEmoji(id)
			}
			null
		}catch(e: Throwable){ null }
	}

	private fun getEmojiId(emoji: Emoji?): Long{
		if(emoji == null) return 0L
		return try{ if(emoji is ModelEmojiCustom) emoji.id else 0L }
		catch(e: Throwable){ ReflectUtils.getField(emoji, "id") as? Long ?: 0L }
	}

	private fun getEmojiName(emoji: Emoji?): String{
		if(emoji == null) return ""
		return try{
			if(emoji is ModelEmojiCustom) emoji.name else (emoji as ModelEmojiUnicode).surrogates
		}catch(e: Throwable){
			(ReflectUtils.getField(emoji, "name") as? String) ?: (ReflectUtils.getField(emoji, "surrogates") as? String) ?: ""
		}
	}
}

class Settings(private val settings: SettingsAPI) : SettingsPage(){
	override fun onViewBound(view: View){
		super.onViewBound(view)
		setActionBarTitle("SyncFav Settings")
		val ctx = view.context

		val enableToggle = Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.SWITCH, "Enable Periodic Sync", "Keep syncing favorite emojis in the background while Aliucord is open.")
		enableToggle.isChecked = settings.getBool("periodic_sync_enabled", true)
		addView(enableToggle)

		val delayHeader = TextView(ctx, null, 0, Utils.getResId("UiKit_Settings_Item_Header", "style"))
		delayHeader.text = "Sync Delay (Seconds)"
		delayHeader.setPadding(DimenUtils.dpToPx(16), DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(8))
		val textColorId = Utils.getResId("primary_dark_200", "color")
		if(textColorId != 0) delayHeader.setTextColor(ctx.getColor(textColorId))
		else delayHeader.setTextColor(Color.WHITE)
		addView(delayHeader)

		val delayInput = TextInput(ctx)
		delayInput.editText.inputType = InputType.TYPE_CLASS_NUMBER
		val currentDelay = settings.getLong("sync_delay", 60L)
		delayInput.editText.setText(currentDelay.toString())
		delayInput.setHint("Minimum: 5, Max: 86400 (24h)")
		addView(delayInput)

		val saveBtn = Button(ctx)
		saveBtn.text = "Save Settings"
		val p = DimenUtils.dpToPx(16)
		saveBtn.setPadding(0, p, 0, p)

		val params = android.widget.LinearLayout.LayoutParams(
			android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 
			android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
		)
		params.setMargins(p, p, p, p)
		saveBtn.layoutParams = params

		saveBtn.setOnClickListener {
			val inputStr = delayInput.editText.text.toString()
			val inputVal = inputStr.toLongOrNull()

			if(inputVal == null || inputVal < 5 || inputVal > 86400){
				Utils.showToast("Invalid Time: Must be between 5s and 86400s")
				delayInput.editText.error = "Invalid Range"
			}else{
				settings.setBool("periodic_sync_enabled", enableToggle.isChecked)
				settings.setLong("sync_delay", inputVal)
				delayInput.editText.error = null
				SyncFav.instance.scheduleSync()
				Utils.showToast("Settings Saved & Periodic Sync Restarted")
				close()
			}
		}
		addView(saveBtn)
	}

}
