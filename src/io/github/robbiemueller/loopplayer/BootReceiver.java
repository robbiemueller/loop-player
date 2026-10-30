package io.github.robbiemueller.loopplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens the player as soon as the sign finishes booting. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Intent start = new Intent(context, PlayerActivity.class);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(start);
    }
}
