# SyncFav

Backports the sync feature for favorite emojis from modern Discord clients.

## Notice
If Periodic Sync is enabled, it's recommended to set a minimum of a 7 second Sync Delay to avoid some kind of rate limit by Discord. Though the plugin allows setting it down to 5 seconds.

## Known issues
- `Remove from Favorites` sometimes doesn't update the Favorite category, I honestly don't know why. The `Remove from Favorites` button on the menu of a synced emoji will also not update to `Add to Favorites` unless you reopen the menu.
- Syncing emojis to modern clients might sometimes not get registered, possibly a rate limit by Discord or something.
