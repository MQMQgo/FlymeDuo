/*
 * Copyright (C) 2026 FlymeDuo contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.yixing233.hyperduo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

import androidx.annotation.RequiresApi;

import java.util.List;

/** Watches cellular registration and display type separately for each active SIM slot. */
final class MobileNetworkPresence {
    private static final int UNKNOWN = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    private static final int NO_SERVICE_READING = -1;
    private static final int NO_OVERRIDE = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE;
    private static final int NO_SUBSCRIPTION = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private static final Card ABSENT = new Card(NO_SUBSCRIPTION, NO_SERVICE_READING,
            UNKNOWN, UNKNOWN, NO_OVERRIDE);
    private static volatile Snapshot snapshot = new Snapshot(ABSENT, ABSENT, NO_SUBSCRIPTION, false);

    private MobileNetworkPresence() {}

    static Snapshot snapshot() {
        return snapshot;
    }

    static void start(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getMainExecutor().execute(() -> new Tracker(context).start());
        }
    }

    record Snapshot(Card sim1, Card sim2, int dataSubscription, boolean airplaneMode) {
        String label(int source, String stockType, boolean followNrAdvanced) {
            if (source != Prefs.TYPE_SIM_DATA && sim1.active() && sim2.active()) {
                return (source == Prefs.TYPE_SIM_2 ? sim2 : sim1).label(followNrAdvanced);
            }
            final Card data = sim1.subscriptionId == dataSubscription ? sim1 : sim2;
            return data.active() && data.subscriptionId == dataSubscription && followNrAdvanced
                    && "5G".equals(stockType) && data.nrAdvanced() ? "5GA" : stockType;
        }

        boolean hasNoService() {
            return !airplaneMode && (sim1.noService() || sim2.noService());
        }
    }

    record Card(int subscriptionId, int serviceState, int radioType, int displayType, int override) {
        boolean active() {
            return subscriptionId != NO_SUBSCRIPTION;
        }

        boolean noService() {
            return active() && (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                    || serviceState == ServiceState.STATE_EMERGENCY_ONLY);
        }

        boolean nrAdvanced() {
            return override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED;
        }

        String label(boolean followNrAdvanced) {
            if (noService() || serviceState == ServiceState.STATE_POWER_OFF) {
                return "";
            }
            // DisplayInfo carries the NSA/Advanced badge. Fall back to the registered
            // cellular RAT when a secondary SIM has no packet-data display type.
            switch (override) {
                case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED:
                    return followNrAdvanced ? "5GA" : "5G";
                case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA:
                case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE:
                    return "5G";
                case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA:
                case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO:
                    return "4G+";
                default:
                    break;
            }
            final int type = displayType == UNKNOWN || displayType == TelephonyManager.NETWORK_TYPE_IWLAN
                    ? radioType : displayType;
            return switch (type) {
                case TelephonyManager.NETWORK_TYPE_NR -> "5G";
                case TelephonyManager.NETWORK_TYPE_LTE -> "4G";
                case TelephonyManager.NETWORK_TYPE_HSPAP -> "H+";
                case TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
                        TelephonyManager.NETWORK_TYPE_HSPA -> "H";
                case TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
                        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B,
                        TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G";
                case TelephonyManager.NETWORK_TYPE_EDGE -> "E";
                case TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM,
                        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
                        TelephonyManager.NETWORK_TYPE_IDEN -> "2G";
                default -> "";
            };
        }
    }

    /** Subscription changes and listener callbacks are serialized on the main executor. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Tracker {
        private final Context context;
        private final SubscriptionManager subscriptions;
        private final TelephonyManager telephony;
        private final SimCallback[] callbacks = new SimCallback[TrioState.SIM_SLOTS];

        Tracker(Context context) {
            this.context = context;
            subscriptions = context.getSystemService(SubscriptionManager.class);
            telephony = context.getSystemService(TelephonyManager.class);
        }

        void start() {
            if (subscriptions == null || telephony == null) {
                return;
            }
            try {
                subscriptions.addOnSubscriptionsChangedListener(context.getMainExecutor(),
                        new SubscriptionManager.OnSubscriptionsChangedListener() {
                            @Override
                            public void onSubscriptionsChanged() {
                                updateSubscriptions();
                            }
                        });
            } catch (RuntimeException ignored) {
                // The initial query and system broadcasts can still supply the active slots.
            }
            final IntentFilter filter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            filter.addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
            filter.addAction("android.intent.action.SIM_STATE_CHANGED");
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateSubscriptions();
                }
            };
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter);
                }
            } catch (RuntimeException ignored) {
                // Subscription and telephony callbacks still refresh the snapshot.
            }
            updateSubscriptions();
        }

        private void updateSubscriptions() {
            final List<SubscriptionInfo> active;
            try {
                active = subscriptions.getActiveSubscriptionInfoList();
            } catch (RuntimeException ignored) {
                publish(false);
                return;
            }
            final int[] ids = { NO_SUBSCRIPTION, NO_SUBSCRIPTION };
            if (active != null) {
                for (SubscriptionInfo info : active) {
                    if (info == null) {
                        continue;
                    }
                    final int slot = info.getSimSlotIndex();
                    if (slot >= 0 && slot < ids.length) {
                        ids[slot] = info.getSubscriptionId();
                    }
                }
            }
            for (int slot = 0; slot < ids.length; slot++) {
                final SimCallback old = callbacks[slot];
                if (old != null && old.subId == ids[slot]) {
                    old.register();
                    continue;
                }
                callbacks[slot] = null;
                if (old != null) {
                    old.unregister();
                }
                if (SubscriptionManager.isValidSubscriptionId(ids[slot])) {
                    final SimCallback next = new SimCallback(slot, ids[slot]);
                    callbacks[slot] = next;
                    next.register();
                }
            }
            publish(TrioState.pollSimsNow());
        }

        private Card cardAt(int slot) {
            final SimCallback current = callbacks[slot];
            return current == null ? ABSENT : current.card;
        }

        private void publish(boolean signalChanged) {
            final Snapshot previous = snapshot;
            int dataSubscription = previous.dataSubscription;
            boolean airplaneMode = previous.airplaneMode;
            try {
                dataSubscription = SubscriptionManager.getDefaultDataSubscriptionId();
                airplaneMode = Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, airplaneMode ? 1 : 0) != 0;
            } catch (RuntimeException ignored) {
                // Keep the latest known settings while telephony is restarting.
            }
            final Snapshot next = new Snapshot(cardAt(0), cardAt(1), dataSubscription, airplaneMode);
            if (signalChanged || !next.equals(previous)) {
                snapshot = next;
                FlymeHooks.invalidateHosts();
            }
        }

        private final class SimCallback extends TelephonyCallback implements
                TelephonyCallback.ServiceStateListener, TelephonyCallback.DisplayInfoListener {
            private final int slot;
            private final int subId;
            private final TelephonyManager forSim;
            private Card card;
            private boolean registered;

            SimCallback(int slot, int subId) {
                this.slot = slot;
                this.subId = subId;
                forSim = telephony.createForSubscriptionId(subId);
                card = new Card(subId, NO_SERVICE_READING, UNKNOWN, UNKNOWN, NO_OVERRIDE);
            }

            void register() {
                if (registered) {
                    return;
                }
                try {
                    forSim.registerTelephonyCallback(context.getMainExecutor(), this);
                    registered = true;
                } catch (RuntimeException ignored) {
                    // The active SIM remains unknown until a listener can be installed.
                }
            }

            void unregister() {
                if (registered) {
                    try {
                        forSim.unregisterTelephonyCallback(this);
                    } catch (RuntimeException ignored) {
                        // The slot check below also rejects queued events from this callback.
                    }
                }
            }

            @Override
            public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
                if (callbacks[slot] == this && info != null) {
                    card = new Card(subId, card.serviceState, card.radioType,
                            info.getNetworkType(), info.getOverrideNetworkType());
                    publish(false);
                }
            }

            @Override
            public void onServiceStateChanged(ServiceState state) {
                if (callbacks[slot] != this || state == null) {
                    return;
                }
                int service = state.getState();
                int type = UNKNOWN;
                if (service != ServiceState.STATE_POWER_OFF) {
                    boolean cellularRecord = false;
                    boolean registeredOnCell = false;
                    for (NetworkRegistrationInfo info : state.getNetworkRegistrationInfoList()) {
                        if (info.getTransportType() != AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                            continue;
                        }
                        cellularRecord = true;
                        if (info.isNetworkRegistered()) {
                            registeredOnCell = true;
                            final int technology = info.getAccessNetworkTechnology();
                            if (technology != UNKNOWN && (type == UNKNOWN
                                    || (info.getDomain() & NetworkRegistrationInfo.DOMAIN_PS) != 0)) {
                                type = technology;
                            }
                        }
                    }
                    if (cellularRecord) {
                        service = registeredOnCell ? ServiceState.STATE_IN_SERVICE
                                : ServiceState.STATE_OUT_OF_SERVICE;
                    }
                }
                card = new Card(subId, service, type, card.displayType, card.override);
                publish(false);
            }
        }
    }
}
