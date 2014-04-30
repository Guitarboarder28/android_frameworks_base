/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.tv;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.TvContract;
import android.tv.ITvInputClient;
import android.tv.ITvInputManager;
import android.tv.ITvInputService;
import android.tv.ITvInputServiceCallback;
import android.tv.ITvInputSession;
import android.tv.ITvInputSessionCallback;
import android.tv.TvInputInfo;
import android.tv.TvInputService;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputChannel;
import android.view.Surface;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.SomeArgs;
import com.android.server.IoThread;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** This class provides a system service that manages television inputs. */
public final class TvInputManagerService extends SystemService {
    // STOPSHIP: Turn debugging off.
    private static final boolean DEBUG = true;
    private static final String TAG = "TvInputManagerService";

    private final Context mContext;

    private final ContentResolver mContentResolver;

    // A global lock.
    private final Object mLock = new Object();

    // ID of the current user.
    private int mCurrentUserId = UserHandle.USER_OWNER;

    // A map from user id to UserState.
    private final SparseArray<UserState> mUserStates = new SparseArray<UserState>();

    private final Handler mLogHandler;

    public TvInputManagerService(Context context) {
        super(context);

        mContext = context;
        mContentResolver = context.getContentResolver();
        mLogHandler = new LogHandler(IoThread.get().getLooper());

        registerBroadcastReceivers();

        synchronized (mLock) {
            mUserStates.put(mCurrentUserId, new UserState());
            buildTvInputListLocked(mCurrentUserId);
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.TV_INPUT_SERVICE, new BinderService());
    }

    private void registerBroadcastReceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                synchronized (mLock) {
                    buildTvInputListLocked(mCurrentUserId);
                }
            }
        };
        monitor.register(mContext, null, UserHandle.ALL, true);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);
    }

    private void buildTvInputListLocked(int userId) {
        UserState userState = getUserStateLocked(userId);
        userState.inputMap.clear();

        if (DEBUG) Slog.d(TAG, "buildTvInputList");
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServices(
                new Intent(TvInputService.SERVICE_INTERFACE), PackageManager.GET_SERVICES);
        for (ResolveInfo ri : services) {
            ServiceInfo si = ri.serviceInfo;
            if (!android.Manifest.permission.BIND_TV_INPUT.equals(si.permission)) {
                Slog.w(TAG, "Skipping TV input " + si.name + ": it does not require the permission "
                        + android.Manifest.permission.BIND_TV_INPUT);
                continue;
            }
            TvInputInfo info = new TvInputInfo(ri);
            if (DEBUG) Slog.d(TAG, "add " + info.getId());
            userState.inputMap.put(info.getId(), info);
        }
    }

    private void switchUser(int userId) {
        synchronized (mLock) {
            if (mCurrentUserId == userId) {
                return;
            }
            // final int oldUserId = mCurrentUserId;
            // TODO: Release services and sessions in the old user state, if needed.
            mCurrentUserId = userId;

            UserState userState = mUserStates.get(userId);
            if (userState == null) {
                userState = new UserState();
            }
            mUserStates.put(userId, userState);
            buildTvInputListLocked(userId);
        }
    }

    private void removeUser(int userId) {
        synchronized (mLock) {
            UserState userState = mUserStates.get(userId);
            if (userState == null) {
                return;
            }
            // Release created sessions.
            for (SessionState state : userState.sessionStateMap.values()) {
                if (state.mSession != null) {
                    try {
                        state.mSession.release();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in release", e);
                    }
                }
            }
            userState.sessionStateMap.clear();

            // Unregister all callbacks and unbind all services.
            for (ServiceState serviceState : userState.serviceStateMap.values()) {
                if (serviceState.mCallback != null) {
                    try {
                        serviceState.mService.unregisterCallback(serviceState.mCallback);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in unregisterCallback", e);
                    }
                }
                serviceState.mClients.clear();
                mContext.unbindService(serviceState.mConnection);
            }
            userState.serviceStateMap.clear();

            mUserStates.remove(userId);
        }
    }

    private UserState getUserStateLocked(int userId) {
        UserState userState = mUserStates.get(userId);
        if (userState == null) {
            throw new IllegalStateException("User state not found for user ID " + userId);
        }
        return userState;
    }

    private ServiceState getServiceStateLocked(String inputId, int userId) {
        UserState userState = getUserStateLocked(userId);
        ServiceState serviceState = userState.serviceStateMap.get(inputId);
        if (serviceState == null) {
            throw new IllegalStateException("Service state not found for " + inputId + " (userId="
                    + userId + ")");
        }
        return serviceState;
    }

    private ITvInputSession getSessionLocked(IBinder sessionToken, int callingUid, int userId) {
        UserState userState = getUserStateLocked(userId);
        SessionState sessionState = userState.sessionStateMap.get(sessionToken);
        if (sessionState == null) {
            throw new IllegalArgumentException("Session state not found for token " + sessionToken);
        }
        // Only the application that requested this session or the system can access it.
        if (callingUid != Process.SYSTEM_UID && callingUid != sessionState.mCallingUid) {
            throw new SecurityException("Illegal access to the session with token " + sessionToken
                    + " from uid " + callingUid);
        }
        ITvInputSession session = sessionState.mSession;
        if (session == null) {
            throw new IllegalStateException("Session not yet created for token " + sessionToken);
        }
        return session;
    }

    private int resolveCallingUserId(int callingPid, int callingUid, int requestedUserId,
            String methodName) {
        return ActivityManager.handleIncomingUser(callingPid, callingUid, requestedUserId, false,
                false, methodName, null);
    }

    private void updateServiceConnectionLocked(String inputId, int userId) {
        UserState userState = getUserStateLocked(userId);
        ServiceState serviceState = userState.serviceStateMap.get(inputId);
        if (serviceState == null) {
            return;
        }
        boolean isStateEmpty = serviceState.mClients.isEmpty()
                && serviceState.mSessionTokens.isEmpty();
        if (serviceState.mService == null && !isStateEmpty && userId == mCurrentUserId) {
            // This means that the service is not yet connected but its state indicates that we
            // have pending requests. Then, connect the service.
            if (serviceState.mBound) {
                // We have already bound to the service so we don't try to bind again until after we
                // unbind later on.
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "bindServiceAsUser(inputId=" + inputId + ", userId=" + userId
                        + ")");
            }

            Intent i = new Intent(TvInputService.SERVICE_INTERFACE).setComponent(
                    userState.inputMap.get(inputId).getComponent());
            mContext.bindServiceAsUser(i, serviceState.mConnection, Context.BIND_AUTO_CREATE,
                    new UserHandle(userId));
            serviceState.mBound = true;
        } else if (serviceState.mService != null && isStateEmpty) {
            // This means that the service is already connected but its state indicates that we have
            // nothing to do with it. Then, disconnect the service.
            if (DEBUG) {
                Slog.d(TAG, "unbindService(inputId=" + inputId + ")");
            }
            mContext.unbindService(serviceState.mConnection);
            userState.serviceStateMap.remove(inputId);
        }
    }

    private void createSessionInternalLocked(ITvInputService service, final IBinder sessionToken,
            final int userId) {
        final SessionState sessionState =
                getUserStateLocked(userId).sessionStateMap.get(sessionToken);
        if (DEBUG) {
            Slog.d(TAG, "createSessionInternalLocked(inputId=" + sessionState.mInputId + ")");
        }

        final InputChannel[] channels = InputChannel.openInputChannelPair(sessionToken.toString());

        // Set up a callback to send the session token.
        ITvInputSessionCallback callback = new ITvInputSessionCallback.Stub() {
            @Override
            public void onSessionCreated(ITvInputSession session) {
                if (DEBUG) {
                    Slog.d(TAG, "onSessionCreated(inputId=" + sessionState.mInputId + ")");
                }
                synchronized (mLock) {
                    sessionState.mSession = session;
                    if (session == null) {
                        removeSessionStateLocked(sessionToken, userId);
                        sendSessionTokenToClientLocked(sessionState.mClient, sessionState.mInputId, null,
                                null, sessionState.mSeq, userId);
                    } else {
                        sendSessionTokenToClientLocked(sessionState.mClient, sessionState.mInputId,
                                sessionToken, channels[0], sessionState.mSeq, userId);
                    }
                    channels[0].dispose();
                }
            }
        };

        // Create a session. When failed, send a null token immediately.
        try {
            service.createSession(channels[1], callback);
        } catch (RemoteException e) {
            Slog.e(TAG, "error in createSession", e);
            removeSessionStateLocked(sessionToken, userId);
            sendSessionTokenToClientLocked(sessionState.mClient, sessionState.mInputId, null, null,
                    sessionState.mSeq, userId);
        }
        channels[1].dispose();
    }

    private void sendSessionTokenToClientLocked(ITvInputClient client, String inputId,
            IBinder sessionToken, InputChannel channel, int seq, int userId) {
        try {
            client.onSessionCreated(inputId, sessionToken, channel, seq);
        } catch (RemoteException exception) {
            Slog.e(TAG, "error in onSessionCreated", exception);
        }

        if (sessionToken == null) {
            // This means that the session creation failed. We might want to disconnect the service.
            updateServiceConnectionLocked(inputId, userId);
        }
    }

    private void removeSessionStateLocked(IBinder sessionToken, int userId) {
        // Remove the session state from the global session state map of the current user.
        UserState userState = getUserStateLocked(userId);
        SessionState sessionState = userState.sessionStateMap.remove(sessionToken);

        // Close the open log entry, if any.
        if (sessionState.mLogUri != null) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = sessionState.mLogUri;
            args.arg2 = System.currentTimeMillis();
            mLogHandler.obtainMessage(LogHandler.MSG_CLOSE_ENTRY, args).sendToTarget();
        }

        // Also remove the session token from the session token list of the current service.
        ServiceState serviceState = userState.serviceStateMap.get(sessionState.mInputId);
        if (serviceState != null) {
            serviceState.mSessionTokens.remove(sessionToken);
        }
        updateServiceConnectionLocked(sessionState.mInputId, userId);
    }

    private final class BinderService extends ITvInputManager.Stub {
        @Override
        public List<TvInputInfo> getTvInputList(int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getTvInputList");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getUserStateLocked(resolvedUserId);
                    return new ArrayList<TvInputInfo>(userState.inputMap.values());
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean getAvailability(final ITvInputClient client, final String inputId,
                int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getAvailability");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getUserStateLocked(resolvedUserId);
                    ServiceState serviceState = userState.serviceStateMap.get(inputId);
                    if (serviceState != null) {
                        // We already know the status of this input service. Return the cached
                        // status.
                        return serviceState.mAvailable;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return false;
        }

        @Override
        public void registerCallback(final ITvInputClient client, final String inputId,
                int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "registerCallback");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    // Create a new service callback and add it to the callback map of the current
                    // service.
                    UserState userState = getUserStateLocked(resolvedUserId);
                    ServiceState serviceState = userState.serviceStateMap.get(inputId);
                    if (serviceState == null) {
                        serviceState = new ServiceState(
                                userState.inputMap.get(inputId), resolvedUserId);
                        userState.serviceStateMap.put(inputId, serviceState);
                    }
                    IBinder iBinder = client.asBinder();
                    if (!serviceState.mClients.contains(iBinder)) {
                        serviceState.mClients.add(iBinder);
                    }
                    if (serviceState.mService != null) {
                        if (serviceState.mCallback != null) {
                            // We already handled.
                            return;
                        }
                        serviceState.mCallback = new ServiceCallback(resolvedUserId);
                        try {
                            serviceState.mService.registerCallback(serviceState.mCallback);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in registerCallback", e);
                        }
                    } else {
                        updateServiceConnectionLocked(inputId, resolvedUserId);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void unregisterCallback(ITvInputClient client, String inputId, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "unregisterCallback");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getUserStateLocked(resolvedUserId);
                    ServiceState serviceState = userState.serviceStateMap.get(inputId);
                    if (serviceState == null) {
                        return;
                    }

                    // Remove this client from the client list and unregister the callback.
                    serviceState.mClients.remove(client.asBinder());
                    if (!serviceState.mClients.isEmpty()) {
                        // We have other clients who want to keep the callback. Do this later.
                        return;
                    }
                    if (serviceState.mService == null || serviceState.mCallback == null) {
                        return;
                    }
                    try {
                        serviceState.mService.unregisterCallback(serviceState.mCallback);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in unregisterCallback", e);
                    } finally {
                        serviceState.mCallback = null;
                        updateServiceConnectionLocked(inputId, resolvedUserId);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void createSession(final ITvInputClient client, final String inputId,
                int seq, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "createSession");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    // Create a new session token and a session state.
                    IBinder sessionToken = new Binder();
                    SessionState sessionState = new SessionState(inputId, client, seq, callingUid);
                    sessionState.mSession = null;

                    // Add them to the global session state map of the current user.
                    UserState userState = getUserStateLocked(resolvedUserId);
                    userState.sessionStateMap.put(sessionToken, sessionState);

                    // Also, add them to the session state map of the current service.
                    ServiceState serviceState = userState.serviceStateMap.get(inputId);
                    if (serviceState == null) {
                        serviceState = new ServiceState(
                                userState.inputMap.get(inputId), resolvedUserId);
                        userState.serviceStateMap.put(inputId, serviceState);
                    }
                    serviceState.mSessionTokens.add(sessionToken);

                    if (serviceState.mService != null) {
                        createSessionInternalLocked(serviceState.mService, sessionToken,
                                resolvedUserId);
                    } else {
                        updateServiceConnectionLocked(inputId, resolvedUserId);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void releaseSession(IBinder sessionToken, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "releaseSession");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    // Release the session.
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).release();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in release", e);
                    }

                    removeSessionStateLocked(sessionToken, resolvedUserId);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setSurface(IBinder sessionToken, Surface surface, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setSurface");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).setSurface(
                                surface);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in setSurface", e);
                    }
                }
            } finally {
                if (surface != null) {
                    // surface is not used in TvInputManagerService.
                    surface.release();
                }
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setVolume(IBinder sessionToken, float volume, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setVolume");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).setVolume(
                                volume);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in setVolume", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void tune(IBinder sessionToken, final Uri channelUri, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "tune");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).tune(channelUri);

                        long currentTime = System.currentTimeMillis();
                        long channelId = ContentUris.parseId(channelUri);

                        // Close the open log entry first, if any.
                        UserState userState = getUserStateLocked(resolvedUserId);
                        SessionState sessionState = userState.sessionStateMap.get(sessionToken);
                        if (sessionState.mLogUri != null) {
                            SomeArgs args = SomeArgs.obtain();
                            args.arg1 = sessionState.mLogUri;
                            args.arg2 = currentTime;
                            mLogHandler.obtainMessage(LogHandler.MSG_CLOSE_ENTRY, args)
                                    .sendToTarget();
                        }

                        // Create a log entry and fill it later.
                        ContentValues values = new ContentValues();
                        values.put(TvContract.WatchedPrograms.WATCH_START_TIME_UTC_MILLIS,
                                currentTime);
                        values.put(TvContract.WatchedPrograms.WATCH_END_TIME_UTC_MILLIS, 0);
                        values.put(TvContract.WatchedPrograms.CHANNEL_ID, channelId);

                        sessionState.mLogUri = mContentResolver.insert(
                                TvContract.WatchedPrograms.CONTENT_URI, values);
                        SomeArgs args = SomeArgs.obtain();
                        args.arg1 = sessionState.mLogUri;
                        args.arg2 = ContentUris.parseId(channelUri);
                        args.arg3 = currentTime;
                        mLogHandler.obtainMessage(LogHandler.MSG_OPEN_ENTRY, args).sendToTarget();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in tune", e);
                        return;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void createOverlayView(IBinder sessionToken, IBinder windowToken, Rect frame,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "createOverlayView");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .createOverlayView(windowToken, frame);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in createOverlayView", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void relayoutOverlayView(IBinder sessionToken, Rect frame, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "relayoutOverlayView");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .relayoutOverlayView(frame);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in relayoutOverlayView", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void removeOverlayView(IBinder sessionToken, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "removeOverlayView");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .removeOverlayView();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in removeOverlayView", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static final class UserState {
        // A mapping from the TV input id to its TvInputInfo.
        private final Map<String, TvInputInfo> inputMap = new HashMap<String,TvInputInfo>();

        // A mapping from the name of a TV input service to its state.
        private final Map<String, ServiceState> serviceStateMap =
                new HashMap<String, ServiceState>();

        // A mapping from the token of a TV input session to its state.
        private final Map<IBinder, SessionState> sessionStateMap =
                new HashMap<IBinder, SessionState>();
    }

    private final class ServiceState {
        private final List<IBinder> mClients = new ArrayList<IBinder>();
        private final List<IBinder> mSessionTokens = new ArrayList<IBinder>();
        private final ServiceConnection mConnection;

        private ITvInputService mService;
        private ServiceCallback mCallback;
        private boolean mBound;
        private boolean mAvailable;

        private ServiceState(TvInputInfo inputInfo, int userId) {
            this.mConnection = new InputServiceConnection(inputInfo, userId);
        }
    }

    private static final class SessionState {
        private final String mInputId;
        private final ITvInputClient mClient;
        private final int mSeq;
        private final int mCallingUid;

        private ITvInputSession mSession;
        private Uri mLogUri;

        private SessionState(String inputId, ITvInputClient client, int seq, int callingUid) {
            this.mInputId = inputId;
            this.mClient = client;
            this.mSeq = seq;
            this.mCallingUid = callingUid;
        }
    }

    private final class InputServiceConnection implements ServiceConnection {
        private final TvInputInfo mTvInputInfo;
        private final int mUserId;

        private InputServiceConnection(TvInputInfo inputInfo, int userId) {
            mUserId = userId;
            mTvInputInfo = inputInfo;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceConnected(inputId=" + mTvInputInfo.getId() + ")");
            }
            synchronized (mLock) {
                ServiceState serviceState = getServiceStateLocked(mTvInputInfo.getId(), mUserId);
                serviceState.mService = ITvInputService.Stub.asInterface(service);

                // Register a callback, if we need to.
                if (!serviceState.mClients.isEmpty() && serviceState.mCallback == null) {
                    serviceState.mCallback = new ServiceCallback(mUserId);
                    try {
                        serviceState.mService.registerCallback(serviceState.mCallback);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in registerCallback", e);
                    }
                }

                // And create sessions, if any.
                for (IBinder sessionToken : serviceState.mSessionTokens) {
                    createSessionInternalLocked(serviceState.mService, sessionToken, mUserId);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceDisconnected(inputId=" + mTvInputInfo.getId() + ")");
            }
        }
    }

    private final class ServiceCallback extends ITvInputServiceCallback.Stub {
        private final int mUserId;

        ServiceCallback(int userId) {
            mUserId = userId;
        }

        @Override
        public void onAvailabilityChanged(String inputId, boolean isAvailable)
                throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "onAvailabilityChanged(inputId=" + inputId + ", isAvailable="
                        + isAvailable + ")");
            }
            synchronized (mLock) {
                ServiceState serviceState = getServiceStateLocked(inputId, mUserId);
                serviceState.mAvailable = isAvailable;
                for (IBinder iBinder : serviceState.mClients) {
                    ITvInputClient client = ITvInputClient.Stub.asInterface(iBinder);
                    client.onAvailabilityChanged(inputId, isAvailable);
                }
            }
        }
    }

    private final class LogHandler extends Handler {
        private static final int MSG_OPEN_ENTRY = 1;
        private static final int MSG_UPDATE_ENTRY = 2;
        private static final int MSG_CLOSE_ENTRY = 3;

        public LogHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_OPEN_ENTRY: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Uri uri = (Uri) args.arg1;
                    long channelId = (long) args.arg2;
                    long time = (long) args.arg3;
                    onOpenEntry(uri, channelId, time);
                    args.recycle();
                    return;
                }
                case MSG_UPDATE_ENTRY: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Uri uri = (Uri) args.arg1;
                    long channelId = (long) args.arg2;
                    long time = (long) args.arg3;
                    onUpdateEntry(uri, channelId, time);
                    args.recycle();
                    return;
                }
                case MSG_CLOSE_ENTRY: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Uri uri = (Uri) args.arg1;
                    long time = (long) args.arg2;
                    onCloseEntry(uri, time);
                    args.recycle();
                    return;
                }
                default: {
                    Slog.w(TAG, "Unhandled message code: " + msg.what);
                    return;
                }
            }
        }

        private void onOpenEntry(Uri uri, long channelId, long watchStarttime) {
            String[] projection = {
                    TvContract.Programs.TITLE,
                    TvContract.Programs.START_TIME_UTC_MILLIS,
                    TvContract.Programs.END_TIME_UTC_MILLIS,
                    TvContract.Programs.DESCRIPTION
            };
            String selection = TvContract.Programs.CHANNEL_ID + "=? AND "
                    + TvContract.Programs.START_TIME_UTC_MILLIS + "<=? AND "
                    + TvContract.Programs.END_TIME_UTC_MILLIS + ">?";
            String[] selectionArgs = {
                    String.valueOf(channelId),
                    String.valueOf(watchStarttime),
                    String.valueOf(watchStarttime)
            };
            String sortOrder = TvContract.Programs.START_TIME_UTC_MILLIS + " ASC";
            Cursor cursor = null;
            try {
                cursor = mContentResolver.query(TvContract.Programs.CONTENT_URI, projection,
                        selection, selectionArgs, sortOrder);
                if (cursor != null && cursor.moveToNext()) {
                    ContentValues values = new ContentValues();
                    values.put(TvContract.WatchedPrograms.TITLE, cursor.getString(0));
                    values.put(TvContract.WatchedPrograms.START_TIME_UTC_MILLIS, cursor.getLong(1));
                    long endTime = cursor.getLong(2);
                    values.put(TvContract.WatchedPrograms.END_TIME_UTC_MILLIS, endTime);
                    values.put(TvContract.WatchedPrograms.DESCRIPTION, cursor.getString(3));
                    mContentResolver.update(uri, values, null, null);

                    // Schedule an update when the current program ends.
                    SomeArgs args = SomeArgs.obtain();
                    args.arg1 = uri;
                    args.arg2 = channelId;
                    args.arg3 = endTime;
                    Message msg = obtainMessage(LogHandler.MSG_UPDATE_ENTRY, args);
                    sendMessageDelayed(msg, endTime - System.currentTimeMillis());
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        private void onUpdateEntry(Uri uri, long channelId, long time) {
            String[] projection = {
                    TvContract.WatchedPrograms.WATCH_START_TIME_UTC_MILLIS,
                    TvContract.WatchedPrograms.WATCH_END_TIME_UTC_MILLIS,
                    TvContract.WatchedPrograms.TITLE,
                    TvContract.WatchedPrograms.START_TIME_UTC_MILLIS,
                    TvContract.WatchedPrograms.END_TIME_UTC_MILLIS,
                    TvContract.WatchedPrograms.DESCRIPTION
            };
            Cursor cursor = null;
            try {
                cursor = mContentResolver.query(uri, projection, null, null, null);
                if (cursor != null && cursor.moveToNext()) {
                    long watchStartTime = cursor.getLong(0);
                    long watchEndTime = cursor.getLong(1);
                    String title = cursor.getString(2);
                    long startTime = cursor.getLong(3);
                    long endTime = cursor.getLong(4);
                    String description = cursor.getString(5);

                    // Do nothing if the current log entry is already closed.
                    if (watchEndTime > 0) {
                        return;
                    }

                    // The current program has just ended. Create a (complete) log entry off the
                    // current entry.
                    ContentValues values = new ContentValues();
                    values.put(TvContract.WatchedPrograms.WATCH_START_TIME_UTC_MILLIS,
                            watchStartTime);
                    values.put(TvContract.WatchedPrograms.WATCH_END_TIME_UTC_MILLIS, time);
                    values.put(TvContract.WatchedPrograms.CHANNEL_ID, channelId);
                    values.put(TvContract.WatchedPrograms.TITLE, title);
                    values.put(TvContract.WatchedPrograms.START_TIME_UTC_MILLIS, startTime);
                    values.put(TvContract.WatchedPrograms.END_TIME_UTC_MILLIS, endTime);
                    values.put(TvContract.WatchedPrograms.DESCRIPTION, description);
                    mContentResolver.insert(TvContract.WatchedPrograms.CONTENT_URI, values);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            // Re-open the current log entry with the next program information.
            onOpenEntry(uri, channelId, time);
        }

        private void onCloseEntry(Uri uri, long watchEndTime) {
            ContentValues values = new ContentValues();
            values.put(TvContract.WatchedPrograms.WATCH_END_TIME_UTC_MILLIS, watchEndTime);
            mContentResolver.update(uri, values, null, null);
        }
    }
}
