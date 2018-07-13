//  Copyright (c) Microsoft Corporation.
//  All rights reserved.
//
//  This code is licensed under the MIT License.
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy
//  of this software and associated documentation files(the "Software"), to deal
//  in the Software without restriction, including without limitation the rights
//  to use, copy, modify, merge, publish, distribute, sublicense, and / or sell
//  copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions :
//
//  The above copyright notice and this permission notice shall be included in
//  all copies or substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
//  THE SOFTWARE.

package com.microsoft.identity.client;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.webkit.WebView;

import com.microsoft.identity.common.adal.internal.AuthenticationConstants;
import com.microsoft.identity.common.adal.internal.util.StringExtensions;
import com.microsoft.identity.common.internal.providers.microsoft.MicrosoftAuthorizationRequest;
import com.microsoft.identity.common.internal.providers.microsoft.MicrosoftAuthorizationResponse;
import com.microsoft.identity.common.internal.providers.microsoft.MicrosoftAuthorizationResult;
import com.microsoft.identity.common.internal.providers.microsoft.microsoftsts.MicrosoftStsAuthorizationRequest;
import com.microsoft.identity.common.internal.providers.microsoft.microsoftsts.MicrosoftStsAuthorizationResult;
import com.microsoft.identity.common.internal.ui.embeddedwebview.AzureActiveDirectoryWebViewClient;
import com.microsoft.identity.common.internal.ui.embeddedwebview.EmbeddedWebViewAuthorizationStrategy;
import com.microsoft.identity.common.internal.ui.embeddedwebview.challengehandlers.IChallengeCompletionCallback;
import com.microsoft.identity.msal.R;

import java.io.Serializable;
import java.util.UUID;

/**
 * Custom tab requires the device to have a browser with custom tab support, chrome with version >= 45 comes with the
 * support and is available on all devices with API version >= 16 . The sdk use chrome custom tab, and before launching
 * chrome custom tab, we need to check if chrome package is in the device. If it is, it's safe to launch the chrome
 * custom tab; Otherwise the sdk will launch chrome.
 * AuthenticationActivity will be responsible for checking if it's safe to launch chrome custom tab, if not, will
 * go with chrome browser, if chrome is not installed, we throw error back.
 */
public final class AuthenticationActivity extends Activity {

    private static final String TAG = AuthenticationActivity.class.getSimpleName(); //NOPMD

    private String mRequestUrl;
    private int mRequestId;
    private boolean mRestarted;
    private UiEvent.Builder mUiEventBuilder;
    private String mTelemetryRequestId;
    private boolean useEmbeddedWebView = false;
    private MsalChromeCustomTabManager mChromeCustomTabManager;
    private EmbeddedWebViewAuthorizationStrategy<
            AzureActiveDirectoryWebViewClient,
            MicrosoftStsAuthorizationRequest,
            MicrosoftStsAuthorizationResult> mEmbeddedWebViewAuthorizationStrategy;
    private MicrosoftStsAuthorizationRequest mAuthorizationRequest;
    private ChallengeCompletionCallback mChallengeCompletionCallback;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (useEmbeddedWebView) {
            Logger.verbose(TAG, null, "Heidi: create request");
            mAuthorizationRequest = getAuthorizationRequestFromIntent(getIntent());
            Logger.verbose(TAG, null, "Heidi: create callback");
            mChallengeCompletionCallback = new ChallengeCompletionCallback();
            Logger.verbose(TAG, null, "Heidi: create webViewClient");
            AzureActiveDirectoryWebViewClient webViewClient = new AzureActiveDirectoryWebViewClient(this, mAuthorizationRequest, mChallengeCompletionCallback);
            Logger.verbose(TAG, null, "Heidi: create EmbeddedWebViewAuthorizationStrategy");
            try {
                setContentView(R.layout.activity_authentication);
                final WebView webview = (WebView) this.findViewById(R.id.webview);
                        //(WebView) this.findViewById(this.getResources().getIdentifier("webView1", "id", this.getPackageName()));
                mEmbeddedWebViewAuthorizationStrategy = new EmbeddedWebViewAuthorizationStrategy<>(webViewClient, webview);
            } catch (final Exception exception) {
                Logger.warning(TAG, null, "Heidi: exception thrown when create the strategy.");
            }
        } else {
            mChromeCustomTabManager = new MsalChromeCustomTabManager(this);
        }

        // If activity is killed by the os, savedInstance will be the saved bundle.
        if (savedInstanceState != null) {
            Logger.verbose(TAG, null, "AuthenticationActivity is re-created after killed by the os.");
            mRestarted = true;
            mTelemetryRequestId = savedInstanceState.getString(Constants.TELEMETRY_REQUEST_ID);
            mUiEventBuilder = new UiEvent.Builder();
            return;
        }

        final Intent data = getIntent();
        if (data == null) {
            sendError(MsalClientException.UNRESOLVABLE_INTENT, "Received null data intent from caller");
            return;
        }

        //mRequestUrl = data.getStringExtra(Constants.REQUEST_URL_KEY);
        mRequestId = data.getIntExtra(Constants.REQUEST_ID, 0);
        /*if (MsalUtils.isEmpty(mRequestUrl)) {
            sendError(MsalClientException.UNRESOLVABLE_INTENT, "Request url is not set on the intent");
            return;
        }

        // We'll use custom tab if the chrome installed on the device comes with custom tab support(on 45 and above it
        // does). If the chrome package doesn't contain the support, we'll use chrome to launch the UI.
        if (MsalUtils.getChromePackage(this.getApplicationContext()) == null) {
            Logger.info(TAG, null, "Chrome is not installed on the device, cannot continue with auth.");
            sendError(MsalClientException.CHROME_NOT_INSTALLED, "Chrome is not installed on the device, cannot proceed with auth");
            return;
        }*/

        mTelemetryRequestId = data.getStringExtra(Constants.TELEMETRY_REQUEST_ID);
        mUiEventBuilder = new UiEvent.Builder();
        Telemetry.getInstance().startEvent(mTelemetryRequestId, mUiEventBuilder);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!useEmbeddedWebView) {
            mChromeCustomTabManager.bindCustomTabsService();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!useEmbeddedWebView) {
            mChromeCustomTabManager.unbindCustomTabsService();
        }
    }

    /**
     * OnNewIntent will be called before onResume.
     *
     * @param intent
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Logger.info(TAG, null, "onNewIntent is called, received redirect from system webview.");
        final String url = intent.getStringExtra(Constants.CUSTOM_TAB_REDIRECT);

        final Intent resultIntent = new Intent();
        resultIntent.putExtra(AuthenticationConstants.Browser.AUTHORIZATION_FINAL_URL, url);
        returnToCaller(Constants.UIResponse.AUTH_CODE_COMPLETE,
                resultIntent);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mRestarted) {
            cancelRequest();
            return;
        }

        mRestarted = true;

        mRequestUrl = this.getIntent().getStringExtra(Constants.REQUEST_URL_KEY);

        Logger.infoPII(TAG, null, "Request to launch is: " + mRequestUrl);
        if (!useEmbeddedWebView) {
            mChromeCustomTabManager.launchChromeTabOrBrowserForUrl(mRequestUrl);
        } else {
            mEmbeddedWebViewAuthorizationStrategy.requestAuthorization(mAuthorizationRequest);
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(Constants.REQUEST_URL_KEY, mRequestUrl);
        outState.putString(Constants.TELEMETRY_REQUEST_ID, mTelemetryRequestId);
    }

    /**
     * Cancels the auth request.
     */
    void cancelRequest() {
        Logger.verbose(TAG, null, "Cancel the authentication request.");
        mUiEventBuilder.setUserDidCancel();
        returnToCaller(Constants.UIResponse.CANCEL, new Intent());
    }

    /**
     * Return the error back to caller.
     *
     * @param resultCode The result code to return back.
     * @param data       {@link Intent} contains the detailed result.
     */
    private void returnToCaller(final int resultCode, final Intent data) {
        Logger.info(TAG, null, "Return to caller with resultCode: " + resultCode + "; requestId: " + mRequestId);
        data.putExtra(Constants.REQUEST_ID, mRequestId);

        if (null != mUiEventBuilder) {
            Telemetry.getInstance().stopEvent(mTelemetryRequestId, mUiEventBuilder);
        }

        setResult(resultCode, data);
        this.finish();
    }

    /**
     * Send error back to caller with the error description.
     *
     * @param errorCode        The error code to send back.
     * @param errorDescription The error description to send back.
     */
    private void sendError(final String errorCode, final String errorDescription) {
        Logger.info(TAG, null, "Sending error back to the caller, errorCode: " + errorCode + "; errorDescription"
                + errorDescription);
        final Intent errorIntent = new Intent();
        errorIntent.putExtra(Constants.UIResponse.ERROR_CODE, errorCode);
        errorIntent.putExtra(Constants.UIResponse.ERROR_DESCRIPTION, errorDescription);
        returnToCaller(Constants.UIResponse.AUTH_CODE_ERROR, errorIntent);
    }

    private MicrosoftStsAuthorizationRequest getAuthorizationRequestFromIntent(final Intent callingIntent) {
        Logger.verbose(TAG, null, "Heidi: Begin to generate the auth request.");
        MicrosoftStsAuthorizationRequest authRequest = null;
        Serializable request = callingIntent
                .getSerializableExtra(AuthenticationConstants.Browser.REQUEST_MESSAGE);

        if (request instanceof MicrosoftStsAuthorizationRequest) {
            Logger.verbose(TAG, null, "Heidi: Finish generating the auth request.");
            authRequest = (MicrosoftStsAuthorizationRequest) request;
        }
        return authRequest;
    }

    class ChallengeCompletionCallback implements IChallengeCompletionCallback {
        @Override
        public void onChallengeResponseReceived(final int returnCode, final Intent responseIntent) {
            Logger.verbose(TAG, null, "onChallengeResponseReceived:" + returnCode);

            if (mAuthorizationRequest == null) {
                Logger.warning(TAG, null, "Request object is null");
            } else {
                // set request id related to this response to send the delegateId
                Logger.verbose(TAG, null,
                        "Set request id related to response. "
                                + "REQUEST_ID for caller returned to:" + mAuthorizationRequest.getCorrelationId());
                responseIntent.putExtra(AuthenticationConstants.Browser.REQUEST_ID, mAuthorizationRequest.getCorrelationId());
            }

            setResult(returnCode, responseIntent);
            finish();
        }

        @Override
        public void setPKeyAuthStatus(final boolean status) {
            Logger.verbose(TAG, null, "setPKeyAuthStatus:" + status);
        }
    }
}
