/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2010-2015 Carnegie Mellon University
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package edu.cmu.cylab.starslinger.transaction;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import android.content.Context;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import edu.cmu.cylab.starslinger.MessagingException;
import edu.cmu.cylab.starslinger.MyLog;
import edu.cmu.cylab.starslinger.R;
import edu.cmu.cylab.starslinger.SafeSlinger;
import edu.cmu.cylab.starslinger.SafeSlingerConfig;
import edu.cmu.cylab.starslinger.crypto.CryptoMsgProvider;
import edu.cmu.cylab.starslinger.exchange.CheckedX509TrustManager;

/**
 * This class does all of the TCP connection setup to the server and handles the
 * HTTP functions GET and POST. In addition to basic GET and POST, it also has
 * web_spate specific functions to get the group size, get the commitments,
 * create the group on the server, send data, ....
 */
public class WebEngine {
    private static final String TAG = SafeSlingerConfig.LOG_TAG;

    private String mUrlPrefix;
    private String mHost;
    private String mUrlSuffix;
    private boolean mCancelable = false;
    private int mVersion;
    private int mVersionLen = 4;
    private HttpsURLConnection mConn;
    private long mTxTotalBytes;
    private long mTxStartBytes;
    private long mRxStartBytes;
    private long mTxCurrentBytes;
    private long mRxCurrentBytes;
    private int mLatestServerVersion = 0;
    private Context mCtx;

    private boolean mNotRegistered = false;

    public WebEngine(Context ctx, String hostName) {
        mCtx = ctx;
        mHost = hostName;
        mUrlSuffix = SafeSlingerConfig.HTTPURL_SUFFIX;
        if (SafeSlingerConfig.isDebug()) {
            mUrlPrefix = SafeSlingerConfig.HTTPURL_PREFIX_BETA;
        } else {
            mUrlPrefix = SafeSlingerConfig.HTTPURL_PREFIX;
        }

        mVersion = SafeSlingerConfig.getVersionCode();
    }

    private byte[] doPost(String uri, byte[] requestBody) throws MessagingException {
        mCancelable = false;

        byte[] reqData = null;
        long startTime = SystemClock.elapsedRealtime();
        int statCode = 0;
        String statMsg = "";
        String error = "";
        boolean responseAvailable = false;

        try {
            // sets up parameters
            KeyStore trusted = null;
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {
                new CheckedX509TrustManager(trusted)
            }, null);
            URL url = new URL(uri);
            mConn = (HttpsURLConnection) url.openConnection();
            mConn.setSSLSocketFactory(context.getSocketFactory());
            mConn.setRequestMethod("POST");
            mConn.setRequestProperty("Content-Type", "application/octet-stream; charset=utf-8");
            mConn.setRequestProperty("Expect", "100-continue");
            mConn.setDoOutput(true);
            mConn.setDoInput(true);

            // Execute HTTP Post Request
            OutputStream os = mConn.getOutputStream();
            os.write(requestBody);
            os.flush();
            os.close();
            responseAvailable = true;

            // handle issues
            statCode = mConn.getResponseCode();
            statMsg = mConn.getResponseMessage();
            if (statCode != HttpURLConnection.HTTP_OK) {
                // contains useful data for users, do not swallow, handle
                // properly
                error = (String.format(mCtx.getString(R.string.error_HttpCode), statCode) + ", \'"
                        + statMsg + "\'");
            } else {
                // read output
                InputStream is = ((mConn.getInputStream()));
                byte[] buffer = new byte[8192];
                int bytesRead;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                reqData = baos.toByteArray();
                is.close();
            }
        } catch (UnsupportedEncodingException e) {
            error = e.getLocalizedMessage() + " (" + e.getClass().getSimpleName() + ")";
        } catch (java.io.IOException e) {
            // just show a simple Internet connection error, so as not to
            // confuse users
            e.printStackTrace();
            error = mCtx.getString(R.string.error_CorrectYourInternetConnection);
        } catch (RuntimeException e) {
            error = e.getLocalizedMessage() + " (" + e.getClass().getSimpleName() + ")";
        } catch (OutOfMemoryError e) {
            error = mCtx.getString(R.string.error_OutOfMemoryError);
        } catch (KeyManagementException e) {
            error = e.getLocalizedMessage() + " (" + e.getClass().getSimpleName() + ")";
        } catch (NoSuchAlgorithmException e) {
            error = e.getLocalizedMessage() + " (" + e.getClass().getSimpleName() + ")";
        } catch (KeyStoreException e) {
            error = e.getLocalizedMessage() + " (" + e.getClass().getSimpleName() + ")";
        } finally {
            long msDelta = SystemClock.elapsedRealtime() - startTime;
            if (responseAvailable) {
                Log.d(TAG, uri + ", " + requestBody.length + "b sent, "
                        + (reqData != null ? reqData.length : 0) + "b recv, " + statCode
                        + " code, " + msDelta + "ms");
            }
        }

        if (!TextUtils.isEmpty(error) || reqData == null) {
            throw new MessagingException(error);
        }
        return reqData;
    }

   
    public void shutdownConnection() {
        if (mConn != null) {
            mConn.disconnect();
            mConn = null;
        }
    }

    /***
     * put a push registration id on the server with a submission token so the
     * user of this key id can authenticate a change later if the registration
     * id they use for the key changes due to migrating to a new device, service
     * migration, registration expiration, others.
     */
    public byte[] postRegistration(String keyId, String senderPushRegId, int notifyType,
            byte[] nonce, String pubSignKey, String priSignKey) throws MessagingException,
            MessageNotFoundException {

        String subToken_Deprecated = new String(); // always empty
        int capacity = mVersionLen //
                + 4 + keyId.length() //
                + 4 + subToken_Deprecated.length() //
                + 4 + senderPushRegId.length() //
                + 4 //
                + 4 + nonce.length //
                + 4 + pubSignKey.length();
        ByteBuffer msg = ByteBuffer.allocate(capacity);
        msg.putInt(mVersion);
        // authentication fields type 1
        msg.putInt(keyId.length());
        msg.put(keyId.getBytes());
        msg.putInt(subToken_Deprecated.length());
        msg.put(subToken_Deprecated.getBytes());
        msg.putInt(senderPushRegId.length());
        msg.put(senderPushRegId.getBytes());
        msg.putInt(notifyType);
        // more authentication fields for type 2
        msg.putInt(nonce.length);
        msg.put(nonce);
        msg.putInt(pubSignKey.length());
        msg.put(pubSignKey.getBytes());
        CryptoMsgProvider p = CryptoMsgProvider.createInstance(SafeSlinger.isLoggable());
        byte[] sig = p.Sign(priSignKey, msg.array());
        ByteBuffer msgSign = ByteBuffer.allocate(msg.capacity() + 4 + sig.length);
        msgSign.put(msg.array());
        msgSign.putInt(sig.length);
        msgSign.put(sig);

        byte[] resp = doPost(mUrlPrefix + mHost + "/postRegistration" + mUrlSuffix, msgSign.array());

        resp = handleResponseExceptions(resp, 0);

        return resp;
    }

    /**
     * put a message, file, its meta-data, and retrieval id on the server
     * 
     * @throws MessageNotFoundException
     */
    public byte[] postMessage(byte[] msgHashBytes, byte[] msgData, byte[] fileData,
            String recipientPushRegId, int notifyType) throws MessagingException,
            MessageNotFoundException {

        handleSizeRestictions(fileData);

        int capacity = mVersionLen //
                + 4 + msgHashBytes.length //
                + 4 + recipientPushRegId.length() //
                + 4 + msgData.length //
                + 4 + fileData.length //
                + 4;
        ByteBuffer msg = ByteBuffer.allocate(capacity);
        msg.putInt(mVersion);
        msg.putInt(msgHashBytes.length);
        msg.put(msgHashBytes);
        msg.putInt(recipientPushRegId.length());
        msg.put(recipientPushRegId.getBytes());
        msg.putInt(msgData.length);
        msg.put(msgData);
        msg.putInt(fileData.length);
        msg.put(fileData);
        msg.putInt(notifyType);

        mNotRegistered = false;

        byte[] resp = doPost(mUrlPrefix + mHost + "/postMessage" + mUrlSuffix, msg.array());

        mNotRegistered = isNotRegisteredErrorCodes(resp);

        resp = handleResponseExceptions(resp, 0);

        return resp;
    }

    /**
     * get a file, based on the retrieval id from the server
     * 
     * @throws MessageNotFoundException
     */
    public byte[] getFile(byte[] msgHashBytes) throws MessagingException, MessageNotFoundException {

        ByteBuffer msg = ByteBuffer.allocate(mVersionLen //
                + 4 + msgHashBytes.length //
        );
        msg.putInt(mVersion);
        msg.putInt(msgHashBytes.length);
        msg.put(msgHashBytes);

        byte[] resp = doPost(mUrlPrefix + mHost + "/getFile" + mUrlSuffix, msg.array());

        resp = handleResponseExceptions(resp, 0);

        return resp;
    }

    /**
     * get a message meta-data, based on the retrieval id from the server
     * 
     * @throws MessageNotFoundException
     */
    public byte[] getMessage(byte[] msgHashBytes) throws MessagingException,
            MessageNotFoundException {

        ByteBuffer msg = ByteBuffer.allocate(mVersionLen //
                + 4 + msgHashBytes.length //
        );
        msg.putInt(mVersion);
        msg.putInt(msgHashBytes.length);
        msg.put(msgHashBytes);

        byte[] resp = doPost(mUrlPrefix + mHost + "/getMessage" + mUrlSuffix, msg.array());

        resp = handleResponseExceptions(resp, 0);

        return resp;
    }

    /**
     * get pending messages from the server
     * 
     * @throws MessageNotFoundException
     */
    public byte[] getMessageNoncesByToken(String recipientPushRegId) throws MessagingException,
            MessageNotFoundException {

        if (TextUtils.isEmpty(recipientPushRegId)) {
            return null;
        }
        int capacity = mVersionLen //
                + 4 + recipientPushRegId.length() //
                + 4;
        ByteBuffer msg = ByteBuffer.allocate(capacity);
        msg.putInt(mVersion);
        msg.putInt(recipientPushRegId.length());
        msg.put(recipientPushRegId.getBytes());
        msg.putInt(1); // numquery is deprecated

        byte[] resp = doPost(mUrlPrefix + mHost + "/getMessageNoncesByToken" + mUrlSuffix,
                msg.array());

        resp = handleResponseExceptions(resp, 0);

        return resp;
    }

    private void handleMessagingErrorCodes(byte[] resp) throws MessagingException,
            MessageNotFoundException {
        String checkError = new String(resp) + "";
        if (checkError.contains(C2DMessaging.ERRMSG_ERROR_PREFIX)) {

            // Errors possible with C2DM and GCM
            if (checkError.contains(C2DMessaging.ERRMSG_QUOTA_EXCEEDED)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgQuotaExceeded));
            } else if (checkError.contains(C2DMessaging.ERRMSG_DEVICE_QUOTA_EXCEEDED)) {
                throw new MessagingException(
                        mCtx.getString(R.string.error_PushMsgDeviceQuotaExceeded));
            } else if (checkError.contains(C2DMessaging.ERRMSG_INVALID_REGISTRATION)) {
                throw new MessagingException(
                        mCtx.getString(R.string.error_PushMsgInvalidRegistration));
            } else if (checkError.contains(C2DMessaging.ERRMSG_NOT_REGISTERED)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgNotRegistered));
            } else if (checkError.contains(C2DMessaging.ERRMSG_MESSAGE_TOO_BIG)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgMessageTooBig));
            } else if (checkError.contains(C2DMessaging.ERRMSG_MISSING_COLLAPSE_KEY)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgNotSucceed));
            } else if (checkError.contains(C2DMessaging.ERRMSG_NOTIFCATION_FAIL)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgNotSucceed));
            } else if (checkError.contains(C2DMessaging.ERRMSG_SERVICE_FAIL)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgServiceFail));
            } else if (checkError.contains(C2DMessaging.ERRMSG_MESSAGE_NOT_FOUND)) {
                throw new MessageNotFoundException(
                        mCtx.getString(R.string.error_PushMsgMessageNotFound));

                // Errors possible with GCM only below...
            } else if (checkError.contains(C2DMessaging.ERRMSG_DEVICE_MESSAGE_RATE_EXCEEDED)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgQuotaExceeded));
            } else if (checkError.contains(C2DMessaging.ERRMSG_INTERNAL_SERVER_ERROR)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgServiceFail));
            } else if (checkError.contains(C2DMessaging.ERRMSG_INVALID_DATA_KEY)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgServiceFail));
            } else if (checkError.contains(C2DMessaging.ERRMSG_INVALID_PACKAGE_NAME)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgNotSucceed));
            } else if (checkError.contains(C2DMessaging.ERRMSG_INVALID_TTL)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgNotSucceed));
            } else if (checkError.contains(C2DMessaging.ERRMSG_MISMATCH_SENDER_ID)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgNotSucceed));
            } else if (checkError.contains(C2DMessaging.ERRMSG_MISSING_REGISTRATION)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgNotRegistered));
            } else if (checkError.contains(C2DMessaging.ERRMSG_UNAVAILABLE)) {
                throw new MessagingException(mCtx.getString(R.string.error_PushMsgServiceFail));
            }
        }
    }

    private boolean isNotRegisteredErrorCodes(byte[] resp) {
        String checkError = new String(resp) + "";
        if (checkError.contains(C2DMessaging.ERRMSG_ERROR_PREFIX)) {
            if (checkError.contains(C2DMessaging.ERRMSG_INVALID_REGISTRATION)) {
                return true;
            } else if (checkError.contains(C2DMessaging.ERRMSG_NOT_REGISTERED)) {
                return true;
            }
        }
        return false;
    }

    private byte[] handleResponseExceptions(byte[] resp, int errMax) throws MessagingException,
            MessageNotFoundException {

        int firstInt = 0;
        ByteBuffer result = ByteBuffer.wrap(resp);
        if (mCancelable)
            throw new MessagingException(mCtx.getString(R.string.error_WebCancelledByUser));
        else if (resp == null)
            throw new MessagingException(mCtx.getString(R.string.error_ServerNotResponding));
        else if (resp.length < 4)
            throw new MessagingException(mCtx.getString(R.string.error_ServerNotResponding));
        else {
            firstInt = result.getInt();
            byte[] bytes = new byte[result.remaining()];
            result.get(bytes);
            if (firstInt <= errMax) { // error int
                MyLog.e(TAG, "server error code: " + firstInt);

                // first, look for expected error string codes from 3rd-party
                handleMessagingErrorCodes(resp);

                // second, use message directly from server
                throw new MessagingException(String.format(
                        mCtx.getString(R.string.error_ServerAppMessage), new String(bytes).trim()));
            }
            // else strip off server version
            mLatestServerVersion = firstInt;
            return bytes;
        }
    }

    private void handleSizeRestictions(byte[] fileData) throws MessagingException {

        if (fileData.length > SafeSlingerConfig.MAX_FILEBYTES)
            throw new MessagingException(String.format(
                    mCtx.getString(R.string.error_CannotSendFilesOver),
                    SafeSlingerConfig.MAX_FILEBYTES));

        return;
    }

    public boolean isCancelable() {
        return mCancelable;
    }

    public void setCancelable(boolean cancelable) {
        mCancelable = cancelable;
    }

    public long get_txTotalBytes() {
        return mTxTotalBytes;
    }

    public long get_txCurrentBytes() {
        final long totalTxBytes = TrafficStats.getTotalTxBytes();
        if (totalTxBytes != TrafficStats.UNSUPPORTED) {
            mTxCurrentBytes = totalTxBytes - mTxStartBytes;
        } else {
            mTxCurrentBytes = 0;
        }
        return mTxCurrentBytes;
    }

    public long get_rxCurrentBytes() {
        final long totalRxBytes = TrafficStats.getTotalRxBytes();
        if (totalRxBytes != TrafficStats.UNSUPPORTED) {
            mRxCurrentBytes = totalRxBytes - mRxStartBytes;
        } else {
            mRxCurrentBytes = 0;
        }
        return mRxCurrentBytes;
    }

    public static byte[] parseMessageResponse(byte[] resp) {
        ByteBuffer buffer = ByteBuffer.wrap(resp);
        byte[] encfile = null;

        int errCode = buffer.getInt();
        if (errCode != 1)
            return null;

        int fileLen = buffer.getInt();
        encfile = new byte[fileLen];
        try {
            buffer.get(encfile);
            return encfile;
        } catch (BufferUnderflowException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static ArrayList<String> parseGetMessageIdsResponse(byte[] resp) {
        ByteBuffer buffer = ByteBuffer.wrap(resp);
        ArrayList<String> ids = new ArrayList<String>();

        int errCode = buffer.getInt();
        if (errCode != 1)
            return null;

        int numIds = buffer.getInt();
        for (int i = 0; i < numIds; i++) {
            int len = buffer.getInt();
            byte[] msgHash = new byte[len];
            buffer.get(msgHash);
            ids.add(new String(msgHash));
        }
        return ids;
    }

    public boolean isNotRegistered() {
        return mNotRegistered;
    }

    public int getLatestServerVersion() {
        return mLatestServerVersion;
    }
}
