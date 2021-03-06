package mega.privacy.android.app;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

import mega.privacy.android.app.listeners.ChatLogoutListener;
import mega.privacy.android.app.lollipop.listeners.MultipleAttachChatListener;
import mega.privacy.android.app.lollipop.megachat.calls.ChatCallActivity;
import mega.privacy.android.app.snackbarListeners.SnackbarNavigateOption;
import nz.mega.sdk.MegaApiAndroid;
import nz.mega.sdk.MegaChatApiAndroid;
import nz.mega.sdk.MegaChatRoom;
import nz.mega.sdk.MegaUser;

import static mega.privacy.android.app.lollipop.LoginFragmentLollipop.NAME_USER_LOCKED;
import static mega.privacy.android.app.utils.BroadcastConstants.*;
import static mega.privacy.android.app.utils.LogUtil.*;
import static mega.privacy.android.app.utils.Util.*;
import static mega.privacy.android.app.utils.DBUtil.*;
import static mega.privacy.android.app.utils.Constants.*;

public class BaseActivity extends AppCompatActivity {

    protected  MegaApplication app;

    protected MegaApiAndroid megaApi;
    protected MegaApiAndroid megaApiFolder;
    protected MegaChatApiAndroid megaChatApi;

    protected DatabaseHandler dbH;

    private AlertDialog sslErrorDialog;

    private boolean delaySignalPresence = false;

    public BaseActivity() {
        app = MegaApplication.getInstance();

        //Will be checked again and initialized at `onCreate()`
        if (app != null) {
            megaApi = app.getMegaApi();
            megaApiFolder = app.getMegaApiFolder();

            if(isChatEnabled()) {
                megaChatApi = app.getMegaChatApi();
            }

            dbH = app.getDbH();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        logDebug("onCreate");

        super.onCreate(savedInstanceState);
        checkMegaObjects();

        LocalBroadcastManager.getInstance(this).registerReceiver(sslErrorReceiver,
                new IntentFilter(BROADCAST_ACTION_INTENT_SSL_VERIFICATION_FAILED));

        LocalBroadcastManager.getInstance(this).registerReceiver(signalPresenceReceiver,
                new IntentFilter(BROADCAST_ACTION_INTENT_SIGNAL_PRESENCE));

        IntentFilter filter =  new IntentFilter(BROADCAST_ACTION_INTENT_EVENT_ACCOUNT_BLOCKED);
        filter.addAction(ACTION_EVENT_ACCOUNT_BLOCKED);
        LocalBroadcastManager.getInstance(this).registerReceiver(accountBlockedReceiver, filter);
    }

    @Override
    protected void onPause() {
        logDebug("onPause");
        checkMegaObjects();
        app.activityPaused();
        super.onPause();
    }

    @Override
    protected void onResume() {
        logDebug("onResume");
        super.onResume();
        setAppFontSize(this);

        checkMegaObjects();
        app.activityResumed();

        retryConnectionsAndSignalPresence();
    }

    @Override
    protected void onDestroy() {
        logDebug("onDestroy");

        LocalBroadcastManager.getInstance(this).unregisterReceiver(sslErrorReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(signalPresenceReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(accountBlockedReceiver);

        super.onDestroy();
    }

    /**
     * Method to check if exist all required objects (MegaApplication, MegaApiAndroid and MegaChatApiAndroid )
     * or create them if necessary.
     */
    private void checkMegaObjects() {

        if (app == null) {
            app = MegaApplication.getInstance();
        }

        if (app != null) {
            if (megaApi == null){
                megaApi = app.getMegaApi();
            }

            if (megaApiFolder == null) {
                megaApiFolder = app.getMegaApiFolder();
            }

            if(isChatEnabled()){
                if (megaChatApi == null){
                    megaChatApi = app.getMegaChatApi();
                }
            }

            if (dbH == null) {
                dbH = app.getDbH();
            }
        }
    }

    /**
     * Broadcast receiver to manage the errors shown and actions when an account is blocked.
     */
    private BroadcastReceiver accountBlockedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null || !intent.getAction().equals(ACTION_EVENT_ACCOUNT_BLOCKED)) return;

            checkWhyAmIBlocked(intent.getLongExtra(EVENT_NUMBER, -1), intent.getStringExtra(EVENT_TEXT));
        }
    };

    /**
     * Broadcast receiver to manage a possible SSL verification error.
     */
    private BroadcastReceiver sslErrorReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                logDebug("BROADCAST TO MANAGE A SSL VERIFICATION ERROR");
                if (sslErrorDialog != null && sslErrorDialog.isShowing()) return;
                showSSLErrorDialog();
            }
        }
    };

    /**
     * Broadcast to send presence after first launch of app
     */
    private BroadcastReceiver signalPresenceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                logDebug("BROADCAST TO SEND SIGNAL PRESENCE");
                if(delaySignalPresence && megaChatApi != null && megaChatApi.getPresenceConfig() != null && !megaChatApi.getPresenceConfig().isPending()){
                    delaySignalPresence = false;
                    retryConnectionsAndSignalPresence();
                }
            }
        }
    };

    /**
     * Method to display an alert dialog indicating that the MEGA SSL key
     * can't be verified (API_ESSL Error) and giving the user several options.
     */
    private void showSSLErrorDialog(){

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View v = inflater.inflate(R.layout.dialog_three_vertical_buttons, null);
        builder.setView(v);

        TextView title = v.findViewById(R.id.dialog_title);
        TextView text = v.findViewById(R.id.dialog_text);

        Button retryButton = v.findViewById(R.id.dialog_first_button);
        Button openBrowserButton = v.findViewById(R.id.dialog_second_button);
        Button dismissButton = v.findViewById(R.id.dialog_third_button);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.RIGHT;

        title.setText(R.string.ssl_error_dialog_title);
        text.setText(R.string.ssl_error_dialog_text);
        retryButton.setText(R.string.general_retry);
        openBrowserButton.setText(R.string.general_open_browser);
        dismissButton.setText(R.string.general_dismiss);

        sslErrorDialog = builder.create();
        sslErrorDialog.setCancelable(false);
        sslErrorDialog.setCanceledOnTouchOutside(false);

        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sslErrorDialog.dismiss();
                megaApi.reconnect();
                megaApiFolder.reconnect();
            }
        });

        openBrowserButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sslErrorDialog.dismiss();
                Uri uriUrl = Uri.parse("https://mega.nz/");
                Intent launchBrowser = new Intent(Intent.ACTION_VIEW, uriUrl);
                startActivity(launchBrowser);
            }
        });

        dismissButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sslErrorDialog.dismiss();
                megaApi.setPublicKeyPinning(false);
                megaApi.reconnect();
                megaApiFolder.setPublicKeyPinning(false);
                megaApiFolder.reconnect();
            }
        });

        sslErrorDialog.show();
    }

    protected void retryConnectionsAndSignalPresence(){
        logDebug("retryConnectionsAndSignalPresence");
        try{
            if (megaApi != null){
                megaApi.retryPendingConnections();
            }

            if(isChatEnabled()) {
                if (megaChatApi != null) {
                    megaChatApi.retryPendingConnections(false, null);

                    if(megaChatApi.getPresenceConfig() != null && !megaChatApi.getPresenceConfig().isPending()){
                        delaySignalPresence = false;
                        if(!(this instanceof ChatCallActivity) && megaChatApi.isSignalActivityRequired()){
                            logDebug("Send signal presence");
                            megaChatApi.signalPresenceActivity();
                        }
                    }
                    else {
                        delaySignalPresence = true;
                    }
                }
            }
        }
        catch (Exception e){
            logWarning("Exception", e);
        }
    }

    @Override
    public void onBackPressed() {
        retryConnectionsAndSignalPresence();
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN ){
            retryConnectionsAndSignalPresence();
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * Method to display a simple Snackbar.
     *
     * @param view Layout where the snackbar is going to show.
     * @param s Text to shown in the snackbar
     */
    public void showSnackbar (View view, String s) {
        showSnackbar(SNACKBAR_TYPE, view, s, -1);
    }

    /**
     * Method to display a simple or action Snackbar.
     *
     * @param type There are three possible values to this param:
     *            - SNACKBAR_TYPE: creates a simple snackbar
     *            - MESSAGE_SNACKBAR_TYPE: creates an action snackbar which function is to go to Chat section
     *            - NOT_SPACE_SNACKBAR_TYPE: creates an action snackbar which function is to go to Storage-Settings section
     * @param view Layout where the snackbar is going to show.
     * @param s Text to shown in the snackbar
     */
    public void showSnackbar (int type, View view, String s) {
        showSnackbar(type, view, s, -1);
    }

    /**
     * Method to display a simple or action Snackbar.
     *
     * @param type There are three possible values to this param:
     *            - SNACKBAR_TYPE: creates a simple snackbar
     *            - MESSAGE_SNACKBAR_TYPE: creates an action snackbar which function is to go to Chat section
     *            - NOT_SPACE_SNACKBAR_TYPE: creates an action snackbar which function is to go to Storage-Settings section
     * @param view Layout where the snackbar is going to show.
     * @param s Text to shown in the snackbar
     * @param idChat Chat ID. If this param has a valid value the function of MESSAGE_SNACKBAR_TYPE ends in the specified chat.
     *               If the value is -1 (INVALID_HANLDE) the function ends in chats list view.
     */
    public void showSnackbar (int type, View view, String s, long idChat) {
        logDebug(("showSnackbar: " + s));
        Display  display = getWindowManager().getDefaultDisplay();
        DisplayMetrics outMetrics = new DisplayMetrics();
        display.getMetrics(outMetrics);

        Snackbar snackbar = null;
        if (type == MESSAGE_SNACKBAR_TYPE) {
            snackbar = Snackbar.make(view, R.string.sent_as_message, Snackbar.LENGTH_LONG);
        }
        else if (type == NOT_SPACE_SNACKBAR_TYPE) {
            snackbar = Snackbar.make(view, R.string.error_not_enough_free_space, Snackbar.LENGTH_LONG);
        }
        else {
            snackbar = Snackbar.make(view, s, Snackbar.LENGTH_LONG);
        }

        Snackbar.SnackbarLayout snackbarLayout = (Snackbar.SnackbarLayout) snackbar.getView();
        snackbarLayout.setBackground(ContextCompat.getDrawable(this, R.drawable.background_snackbar));

        if (snackbarLayout.getLayoutParams() instanceof CoordinatorLayout.LayoutParams) {
            final CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) snackbarLayout.getLayoutParams();
            params.setMargins(px2dp(8, outMetrics),0,px2dp(8, outMetrics), px2dp(8, outMetrics));
            snackbarLayout.setLayoutParams(params);
        }
        else if (snackbarLayout.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) snackbarLayout.getLayoutParams();
            params.setMargins(px2dp(8, outMetrics),0, px2dp(8, outMetrics), px2dp(8, outMetrics));
            snackbarLayout.setLayoutParams(params);
        }

        switch (type) {
            case SNACKBAR_TYPE: {
                TextView snackbarTextView = (TextView)snackbar.getView().findViewById(android.support.design.R.id.snackbar_text);
                snackbarTextView.setMaxLines(5);
                snackbar.show();
                break;
            }
            case MESSAGE_SNACKBAR_TYPE: {
                snackbar.setAction("SEE", new SnackbarNavigateOption(view.getContext(), idChat));
                snackbar.show();
                break;
            }
            case NOT_SPACE_SNACKBAR_TYPE: {
                snackbar.setAction("Settings", new SnackbarNavigateOption(view.getContext()));
                snackbar.show();
                break;
            }
        }
    }

    /**
     * Method to display a simple Snackbar.
     *
     * @param context Context of the Activity where the snackbar has to be displayed
     * @param outMetrics DisplayMetrics of the current device
     * @param view Layout where the snackbar is going to show.
     * @param s Text to shown in the snackbar
     */
    public static void showSimpleSnackbar(Context context, DisplayMetrics outMetrics, View view, String s) {
        Snackbar snackbar = Snackbar.make(view, s, Snackbar.LENGTH_LONG);
        Snackbar.SnackbarLayout snackbarLayout = (Snackbar.SnackbarLayout) snackbar.getView();
        snackbarLayout.setBackground(ContextCompat.getDrawable(context, R.drawable.background_snackbar));
        final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) snackbarLayout.getLayoutParams();
        params.setMargins(px2dp(8, outMetrics),0,px2dp(8, outMetrics), px2dp(8, outMetrics));
        snackbarLayout.setLayoutParams(params);
        TextView snackbarTextView = (TextView) snackbar.getView().findViewById(android.support.design.R.id.snackbar_text);
        snackbarTextView.setMaxLines(5);
        snackbar.show();
    }

    /**
     * Method for send a file into one or more chats
     *
     * @param context Context of the Activity where the file has to be sent
     * @param chats Chats where the file has to be sent
     * @param fileHandle Handle of the file that has to be sent
     */
    public void sendFileToChatsFromContacts(Context context, ArrayList<MegaChatRoom> chats, long fileHandle){
        logDebug("sendFileToChatsFromContacts");

        MultipleAttachChatListener listener = null;

        if(chats.size()==1){
            listener = new MultipleAttachChatListener(context, chats.get(0).getChatId(), chats.size());
            megaChatApi.attachNode(chats.get(0).getChatId(), fileHandle, listener);
        }
        else{
            listener = new MultipleAttachChatListener(context, -1, chats.size());
            for(int i=0;i<chats.size();i++){
                megaChatApi.attachNode(chats.get(i).getChatId(), fileHandle, listener);
            }
        }
    }

    /**
     * Method to refresh the account details info if necessary.
     */
    protected void refreshAccountInfo(){
        logDebug("refreshAccountInfo");

        //Check if the call is recently
        logDebug("Check the last call to getAccountDetails");
        if(callToAccountDetails()){
            logDebug("megaApi.getAccountDetails SEND");
            app.askForAccountDetails();
        }
    }

    /**
     * Method to show an alert or error when the account has been suspended
     * for any reason
     *
     * @param eventNumber long that determines the event for which the account has been suspended
     * @param stringError string shown as an alert in case there is not any specific action for the event
     */
    public void checkWhyAmIBlocked(long eventNumber, String stringError) {
        Intent intent;

        switch (Long.toString(eventNumber)) {
            case ACCOUNT_NOT_BLOCKED:
//                I am not blocked
                break;
            case COPYRIGHT_ACCOUNT_BLOCK:
                showErrorAlertDialog(getString(R.string.account_suspended_breache_ToS), false, this);
                megaChatApi.logout(new ChatLogoutListener(getApplicationContext()));
                break;
            case MULTIPLE_COPYRIGHT_ACCOUNT_BLOCK:
                showErrorAlertDialog(getString(R.string.account_suspended_multiple_breaches_ToS), false, this);
                megaChatApi.logout(new ChatLogoutListener(getApplicationContext()));
                break;
            case SMS_VERIFICATION_ACCOUNT_BLOCK:
                if (megaApi.smsAllowedState() == 0 || MegaApplication.isVerifySMSShowed()) return;

                MegaApplication.smsVerifyShowed(true);
                String gSession = megaApi.dumpSession();
                //For first login, keep the valid session,
                //after added phone number, the account can use this session to fastLogin
                if (gSession != null) {
                    MegaUser myUser = megaApi.getMyUser();
                    String myUserHandle = null;
                    String lastEmail = null;
                    if (myUser != null) {
                        lastEmail = myUser.getEmail();
                        myUserHandle = myUser.getHandle() + "";
                    }
                    UserCredentials credentials = new UserCredentials(lastEmail, gSession, "", "", myUserHandle);
                    dbH.saveCredentials(credentials);
                }

                logDebug("Show SMS verification activity.");
                intent = new Intent(getApplicationContext(), SMSVerificationActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(NAME_USER_LOCKED, true);
                startActivity(intent);

                break;
            case WEAK_PROTECTION_ACCOUNT_BLOCK:
                if (app.isBlockedDueToWeakAccount() || app.isWebOpenDueToEmailVerification()) {
                    break;
                }
                intent = new Intent(this, WeakAccountProtectionAlertActivity.class);
                startActivity(intent);
                break;
            default:
                showErrorAlertDialog(stringError, false, this);
        }
    }
}
