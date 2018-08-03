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

package android.bluetooth.client.map;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.SparseArray;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.WeakReference;

import javax.obex.ServerSession;

class BluetoothMnsService {

    private static final String TAG = "BluetoothMnsService";

    private static final ParcelUuid MAP_MNS =
            ParcelUuid.fromString("00001133-0000-1000-8000-00805F9B34FB");

    public static final int MNS_RFCOMM_CHANNEL = 22;
    public static final int MNS_L2CAP_PSM = 0x1027;

    static final int MSG_EVENT = 1;
    /* for BluetoothMasClient */
    static final int EVENT_REPORT = 1001;

    /* these are shared across instances */
    static private SocketAcceptor mAcceptThread = null;
    static private Handler mSessionHandler = null;
    static private BluetoothServerSocket mServerSocket = null;
    static private BluetoothMnsObexServerSockets mServerSockets = null;
    static private SparseArray<Handler> mCallbacks = null;
    static private volatile boolean mShutdown = false;         // Used to interrupt socket accept thread

    private static class SessionHandler extends Handler {

        private final WeakReference<BluetoothMnsService> mService;

        SessionHandler(BluetoothMnsService service) {
            mService = new WeakReference<BluetoothMnsService>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "Handler: msg: " + msg.what);

            switch (msg.what) {
                case MSG_EVENT:
                    int instanceId = msg.arg1;

                    synchronized (mCallbacks) {
                        Handler cb = mCallbacks.get(instanceId);

                        if (cb != null) {
                            BluetoothMapEventReport ev = (BluetoothMapEventReport) msg.obj;
                            cb.obtainMessage(EVENT_REPORT, ev).sendToTarget();
                        } else {
                            Log.w(TAG, "Got event for instance which is not registered: "
                                    + instanceId);
                        }
                    }
                    break;
            }
        }
    }

    private class SocketAcceptor implements IObexConnectionHandler {

        private boolean mInterrupted = false;

        /**
         * Called when an unrecoverable error occurred in an accept thread.
         * Close down the server socket, and restart.
         * TODO: Change to message, to call start in correct context.
         */
        @Override
        public synchronized void onAcceptFailed() {
            Log.e(TAG, "OnAcceptFailed");
            mServerSockets = null; // Will cause a new to be created when calling start.
            if (mShutdown) {
                Log.e(TAG, "Failed to accept incomming connection - " + "shutdown");
            }
        }

        @Override
        public synchronized boolean onConnect(BluetoothDevice device, BluetoothSocket socket) {
            Log.d(TAG, "onConnect" + device + " SOCKET: " + socket);
            /* Signal to the service that we have received an incoming connection.*/
            BluetoothMnsObexServer srv = new BluetoothMnsObexServer(mSessionHandler);
            BluetoothMapRfcommTransport transport = new BluetoothMapRfcommTransport(socket);
            try {
                new ServerSession(transport, srv, null);
                return true;
            } catch (IOException e) {
                Log.e(TAG, e.toString());
                return false;
            }
        }
    }

    BluetoothMnsService() {
        Log.v(TAG, "BluetoothMnsService()");

        if (mCallbacks == null) {
            Log.v(TAG, "BluetoothMnsService(): allocating callbacks");
            mCallbacks = new SparseArray<Handler>();
        }

        if (mSessionHandler == null) {
            Log.v(TAG, "BluetoothMnsService(): allocating session handler");
            mSessionHandler = new SessionHandler(this);
        }
    }

    public void registerCallback(int instanceId, Handler callback) {
        Log.v(TAG, "registerCallback()");

        synchronized (mCallbacks) {
            mCallbacks.put(instanceId, callback);

            if (mAcceptThread == null) {
                Log.v(TAG, "registerCallback(): starting MNS server");
                mAcceptThread = new SocketAcceptor();
                Log.v(TAG, "Listen on rfcomm channel " + MNS_RFCOMM_CHANNEL + " l2cap psm " + MNS_L2CAP_PSM);
                mServerSockets = BluetoothMnsObexServerSockets.createWithFixedChannels(mAcceptThread,
                        MNS_RFCOMM_CHANNEL, MNS_L2CAP_PSM);
            }
        }
    }

    public void unregisterCallback(int instanceId) {
        Log.v(TAG, "unregisterCallback()");

        synchronized (mCallbacks) {
            mCallbacks.remove(instanceId);

            if (mCallbacks.size() == 0) {
                Log.v(TAG, "unregisterCallback(): shutting down MNS server");

                if (mServerSockets != null) {
                    mServerSockets.shutdown(false);
                    mServerSockets = null;
                }
            }
        }
    }
}
