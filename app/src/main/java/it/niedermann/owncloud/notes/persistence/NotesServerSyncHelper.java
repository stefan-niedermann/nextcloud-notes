package it.niedermann.owncloud.notes.persistence;

import android.accounts.NetworkErrorException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;

import com.nextcloud.android.sso.AccountImporter;
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppAccountNotFoundException;
import com.nextcloud.android.sso.exceptions.NoCurrentAccountSelectedException;
import com.nextcloud.android.sso.helper.SingleAccountHelper;
import com.nextcloud.android.sso.model.SingleSignOnAccount;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import it.niedermann.owncloud.notes.R;
import it.niedermann.owncloud.notes.persistence.entity.Account;
import it.niedermann.owncloud.notes.shared.model.ISyncCallback;
import it.niedermann.owncloud.notes.shared.model.SyncResultStatus;
import it.niedermann.owncloud.notes.shared.util.SSOUtil;

import static androidx.lifecycle.Transformations.distinctUntilChanged;

/**
 * Helps to synchronize the Database to the Server.
 */
public class NotesServerSyncHelper {

    private static final String TAG = NotesServerSyncHelper.class.getSimpleName();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static NotesServerSyncHelper instance;

    private final NotesDatabase db;
    private final Context context;

    /**
     * Track network connection changes using a {@link BroadcastReceiver}
     */
    private boolean isSyncPossible = false;
    private boolean networkConnected = false;
    private String syncOnlyOnWifiKey;
    private boolean syncOnlyOnWifi;
    private final MutableLiveData<Boolean> syncStatus = new MutableLiveData<>(false);
    private final MutableLiveData<ArrayList<Throwable>> syncErrors = new MutableLiveData<>();

    /**
     * @see <a href="https://stackoverflow.com/a/3104265">Do not make this a local variable.</a>
     */
    @SuppressWarnings("FieldCanBeLocal")
    private final SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener = (SharedPreferences prefs, String key) -> {
        if (syncOnlyOnWifiKey.equals(key)) {
            syncOnlyOnWifi = prefs.getBoolean(syncOnlyOnWifiKey, false);
            updateNetworkStatus();
        }
    };

    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateNetworkStatus();
            if (isSyncPossible() && SSOUtil.isConfigured(context)) {
                new Thread(() -> {
                    try {
                        scheduleSync(db.getAccountDao().getAccountByName(SingleAccountHelper.getCurrentSingleSignOnAccount(context).name), false);
                    } catch (NextcloudFilesAppAccountNotFoundException | NoCurrentAccountSelectedException e) {
                        Log.v(TAG, "Can not select current SingleSignOn account after network changed, do not sync.");
                    }
                }).start();
            }
        }
    };

    // current state of the synchronization
    private final Map<Long, Boolean> syncActive = new HashMap<>();
    private final Map<Long, Boolean> syncScheduled = new HashMap<>();

    // list of callbacks for both parts of synchronization
    private final Map<Long, List<ISyncCallback>> callbacksPush = new HashMap<>();
    private final Map<Long, List<ISyncCallback>> callbacksPull = new HashMap<>();

    private NotesServerSyncHelper(NotesDatabase db) {
        this.db = db;
        this.context = db.getContext();
        this.syncOnlyOnWifiKey = context.getApplicationContext().getResources().getString(R.string.pref_key_wifi_only);

        // Registers BroadcastReceiver to track network connection changes.
        context.getApplicationContext().registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.context.getApplicationContext());
        prefs.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
        syncOnlyOnWifi = prefs.getBoolean(syncOnlyOnWifiKey, false);

        updateNetworkStatus();
    }

    /**
     * Get (or create) instance from NoteServerSyncHelper.
     * This has to be a singleton in order to realize correct registering and unregistering of
     * the BroadcastReceiver, which listens on changes of network connectivity.
     *
     * @param db {@link NotesDatabase}
     * @return NoteServerSyncHelper
     */
    public static synchronized NotesServerSyncHelper getInstance(NotesDatabase db) {
        if (instance == null) {
            instance = new NotesServerSyncHelper(db);
        }
        return instance;
    }

    @Override
    protected void finalize() throws Throwable {
        context.getApplicationContext().unregisterReceiver(networkReceiver);
        super.finalize();
    }

    /**
     * Synchronization is only possible, if there is an active network connection.
     * <p>
     * This method respects the user preference "Sync on Wi-Fi only".
     * <p>
     * NoteServerSyncHelper observes changes in the network connection.
     * The current state can be retrieved with this method.
     *
     * @return true if sync is possible, otherwise false.
     */
    public boolean isSyncPossible() {
        return isSyncPossible;
    }

    public boolean isNetworkConnected() {
        return networkConnected;
    }

    public boolean isSyncOnlyOnWifi() {
        return syncOnlyOnWifi;
    }

    /**
     * Adds a callback method to the NoteServerSyncHelper for the synchronization part push local changes to the server.
     * All callbacks will be executed once the synchronization operations are done.
     * After execution the callback will be deleted, so it has to be added again if it shall be
     * executed the next time all synchronize operations are finished.
     *
     * @param callback Implementation of ISyncCallback, contains one method that shall be executed.
     */
    public void addCallbackPush(Account account, ISyncCallback callback) {
        if (account == null) {
            Log.i(TAG, "ssoAccount is null. Is this a local account?");
            callback.onScheduled();
            callback.onFinish();
        } else {
            if (!callbacksPush.containsKey(account.getId())) {
                callbacksPush.put(account.getId(), new ArrayList<>());
            }
            Objects.requireNonNull(callbacksPush.get(account.getId())).add(callback);
        }
    }

    /**
     * Adds a callback method to the NoteServerSyncHelper for the synchronization part pull remote changes from the server.
     * All callbacks will be executed once the synchronization operations are done.
     * After execution the callback will be deleted, so it has to be added again if it shall be
     * executed the next time all synchronize operations are finished.
     *
     * @param callback Implementation of ISyncCallback, contains one method that shall be executed.
     */
    public void addCallbackPull(Account account, ISyncCallback callback) {
        if (account == null) {
            Log.i(TAG, "ssoAccount is null. Is this a local account?");
            callback.onScheduled();
            callback.onFinish();
        } else {
            if (!callbacksPull.containsKey(account.getId())) {
                callbacksPull.put(account.getId(), new ArrayList<>());
            }
            Objects.requireNonNull(callbacksPull.get(account.getId())).add(callback);
        }
    }

    /**
     * Schedules a synchronization and start it directly, if the network is connected and no
     * synchronization is currently running.
     *
     * @param onlyLocalChanges Whether to only push local changes to the server or to also load the whole list of notes from the server.
     */
    public void scheduleSync(Account account, boolean onlyLocalChanges) {
        if (account == null) {
            Log.i(TAG, SingleSignOnAccount.class.getSimpleName() + " is null. Is this a local account?");
        } else {
            if (syncActive.get(account.getId()) == null) {
                syncActive.put(account.getId(), false);
            }
            Log.d(TAG, "Sync requested (" + (onlyLocalChanges ? "onlyLocalChanges" : "full") + "; " + (Boolean.TRUE.equals(syncActive.get(account.getId())) ? "sync active" : "sync NOT active") + ") ...");
            if (isSyncPossible() && (!Boolean.TRUE.equals(syncActive.get(account.getId())) || onlyLocalChanges)) {
                try {
                    SingleSignOnAccount ssoAccount = AccountImporter.getSingleSignOnAccount(context, account.getAccountName());
                    Log.d(TAG, "... starting now");
                    final NotesClient notesClient = NotesClient.newInstance(account.getPreferredApiVersion(), context);
                    final NotesServerSyncTask syncTask = new NotesServerSyncTask(notesClient, db, account, ssoAccount, onlyLocalChanges) {
                        @Override
                        void onPreExecute() {
                            syncStatus.postValue(true);
                            if (!syncScheduled.containsKey(localAccount.getId()) || syncScheduled.get(localAccount.getId()) == null) {
                                syncScheduled.put(localAccount.getId(), false);
                            }
                            if (!onlyLocalChanges && Boolean.TRUE.equals(syncScheduled.get(localAccount.getId()))) {
                                syncScheduled.put(localAccount.getId(), false);
                            }
                            syncActive.put(localAccount.getId(), true);
                        }

                        @Override
                        void onPostExecute(SyncResultStatus status) {
                            for (Throwable e : exceptions) {
                                Log.e(TAG, e.getMessage(), e);
                            }
                            if (!status.pullSuccessful || !status.pushSuccessful) {
                                syncErrors.postValue(exceptions);
                            }
                            syncActive.put(localAccount.getId(), false);
                            // notify callbacks
                            if (callbacks.containsKey(localAccount.getId()) && callbacks.get(localAccount.getId()) != null) {
                                for (ISyncCallback callback : Objects.requireNonNull(callbacks.get(localAccount.getId()))) {
                                    callback.onFinish();
                                }
                            }
                            db.notifyWidgets();
                            db.updateDynamicShortcuts(localAccount.getId());
                            // start next sync if scheduled meanwhile
                            if (syncScheduled.containsKey(localAccount.getId()) && syncScheduled.get(localAccount.getId()) != null && Boolean.TRUE.equals(syncScheduled.get(localAccount.getId()))) {
                                scheduleSync(localAccount, false);
                            }
                            syncStatus.postValue(false);
                        }
                    };
                    syncTask.addCallbacks(account, callbacksPush.get(account.getId()));
                    callbacksPush.put(account.getId(), new ArrayList<>());
                    if (!onlyLocalChanges) {
                        syncTask.addCallbacks(account, callbacksPull.get(account.getId()));
                        callbacksPull.put(account.getId(), new ArrayList<>());
                    }
                    executor.submit(syncTask);
                } catch (NextcloudFilesAppAccountNotFoundException e) {
                    Log.e(TAG, "... Could not find " + SingleSignOnAccount.class.getSimpleName() + " for account name " + account.getAccountName());
                    e.printStackTrace();
                }
            } else if (!onlyLocalChanges) {
                Log.d(TAG, "... scheduled");
                syncScheduled.put(account.getId(), true);
                if (callbacksPush.containsKey(account.getId()) && callbacksPush.get(account.getId()) != null) {
                    final List<ISyncCallback> callbacks = callbacksPush.get(account.getId());
                    if (callbacks != null) {
                        for (ISyncCallback callback : callbacks) {
                            callback.onScheduled();
                        }
                    } else {
                        Log.w(TAG, "List of push-callbacks was set for account \"" + account.getAccountName() + "\" but it was null");
                    }
                }
            } else {
                Log.d(TAG, "... do nothing");
                if (callbacksPush.containsKey(account.getId()) && callbacksPush.get(account.getId()) != null) {
                    final List<ISyncCallback> callbacks = callbacksPush.get(account.getId());
                    if (callbacks != null) {
                        for (ISyncCallback callback : callbacks) {
                            callback.onScheduled();
                        }
                    } else {
                        Log.w(TAG, "List of push-callbacks was set for account \"" + account.getAccountName() + "\" but it was null");
                    }
                }
            }
        }
    }

    public void updateNetworkStatus() {
        try {
            final ConnectivityManager connMgr = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);

            if (connMgr == null) {
                throw new NetworkErrorException("ConnectivityManager is null");
            }

            final NetworkInfo activeInfo = connMgr.getActiveNetworkInfo();

            if (activeInfo == null) {
                throw new NetworkErrorException("NetworkInfo is null");
            }

            if (activeInfo.isConnected()) {
                networkConnected = true;

                final NetworkInfo networkInfo = connMgr.getNetworkInfo((ConnectivityManager.TYPE_WIFI));

                if (networkInfo == null) {
                    throw new NetworkErrorException("connMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI) is null");
                }

                isSyncPossible = !syncOnlyOnWifi || networkInfo.isConnected();

                if (isSyncPossible) {
                    Log.d(TAG, "Network connection established.");
                } else {
                    Log.d(TAG, "Network connected, but not used because only synced on wifi.");
                }
            } else {
                networkConnected = false;
                isSyncPossible = false;
                Log.d(TAG, "No network connection.");
            }
        } catch (NetworkErrorException e) {
            e.printStackTrace();
            networkConnected = false;
            isSyncPossible = false;
        }
    }

    @NonNull
    public LiveData<Boolean> getSyncStatus() {
        return distinctUntilChanged(this.syncStatus);
    }

    @NonNull
    public LiveData<ArrayList<Throwable>> getSyncErrors() {
        return this.syncErrors;
    }
}