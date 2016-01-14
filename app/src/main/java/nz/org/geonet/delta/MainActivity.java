package nz.org.geonet.delta;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.android.AuthActivity;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    ///////////////////////////////////////////////////////////////////////////
    //                      Your app-specific settings.                      //
    ///////////////////////////////////////////////////////////////////////////

    // Replace this with your app key and secret assigned by Dropbox.
    // Note that this is a really insecure way to do this, and you shouldn't
    // ship code which contains your key & secret in such an obvious way.
    // Obfuscation is good.
    private static final String APP_KEY = "vlnvwv4mu7dz7f3";
    private static final String APP_SECRET = "ej43c8vaty0fsx1";

    ///////////////////////////////////////////////////////////////////////////
    //                      End app-specific settings.                       //
    ///////////////////////////////////////////////////////////////////////////

    // You don't need to change these, leave them alone.
    private static final String ACCOUNT_PREFS_NAME = "prefs";
    private static final String ACCESS_KEY_NAME = "ACCESS_KEY";
    private static final String ACCESS_SECRET_NAME = "ACCESS_SECRET";

    private static final boolean USE_OAUTH1 = false;
    private static final String DROPBOX_DIR = "/";

    DropboxAPI<AndroidAuthSession> mApi;

    private boolean mLoggedIn;

    // Android widgets
    private Button mSubmit;
    private LinearLayout mDisplay;
    private LinearLayout mSearch;
    private EditText mSearchText;


    private final String PHOTO_DIR = "/Photos/";

    private static final int NEW_PICTURE = 1;
    private String mCameraFileName;
    private Button mSearchButton;
    private MenuItem mMenuUnlinkDropBox;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mCameraFileName = savedInstanceState.getString("mCameraFileName");
        }

        // We create a new AuthSession so that we can use the Dropbox API.
        AndroidAuthSession session = buildSession();
        mApi = new DropboxAPI<AndroidAuthSession>(session);

        // Basic Android widgets
        setContentView(R.layout.activity_main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        checkAppKeySetup();

        mSubmit = (Button) findViewById(R.id.auth_button);

        mSubmit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // This logs you out if you're logged in, or vice versa
                if (mLoggedIn) {
                    logOut();
                } else {
                    // Start the remote authentication
                    if (USE_OAUTH1) {
                        mApi.getSession().startAuthentication(MainActivity.this);
                    } else {
                        mApi.getSession().startOAuth2Authentication(MainActivity.this);
                    }
                }
            }
        });

        mDisplay = (LinearLayout) findViewById(R.id.logged_in_display);
        mSearch = (LinearLayout) findViewById(R.id.search_place_container);
        mSearchText = (EditText) findViewById(R.id.txt_search_place);
        mSearchButton = (Button) findViewById(R.id.btn_search_place);
        //
        mSearchText.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (getResources().getString(R.string.search_hint).equals(mSearchText.getText().toString())) {
                    mSearchText.setText(""); //clear hint text
                }
                return true;
            }
        });

        mSearchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String searchText = mSearchText.getText().toString();
                if ("".equals(searchText) || getResources().getString(R.string.search_hint).equals(searchText)) {
                //nothing to search!!
                } else {
                    searchPlacesFromVCard(searchText);
                }
            }
        });

        // Display the proper UI state if logged in or not
        setLoggedIn(mApi.getSession().isLinked());

    }

    /**
     * search places from vcard
     *
     * @param searchText
     */
    private void searchPlacesFromVCard(String searchText) {
        SearchPlaces search = new SearchPlaces(MainActivity.this, mApi, DROPBOX_DIR, mDisplay, searchText);
        search.execute();
    }


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("mCameraFileName", mCameraFileName);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AndroidAuthSession session = mApi.getSession();

        // The next part must be inserted in the onResume() method of the
        // activity from which session.startAuthentication() was called, so
        // that Dropbox authentication completes properly.
        if (session.authenticationSuccessful()) {
            try {
                // Mandatory call to complete the auth
                session.finishAuthentication();

                // Store it locally in our app for later use
                storeAuth(session);
                setLoggedIn(true);
            } catch (IllegalStateException e) {
                showToast("Couldn't authenticate with Dropbox:" + e.getLocalizedMessage());
                Log.i(TAG, "Error authenticating", e);
            }
        }
    }

    // This is what gets called on finishing a media piece to import
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == NEW_PICTURE) {
            // return from file upload
            if (resultCode == Activity.RESULT_OK) {
                Uri uri = null;
                if (data != null) {
                    uri = data.getData();
                }
                if (uri == null && mCameraFileName != null) {
                    uri = Uri.fromFile(new File(mCameraFileName));
                }
                File file = new File(mCameraFileName);

                if (uri != null) {
                    UploadPicture upload = new UploadPicture(this, mApi, PHOTO_DIR, file);
                    upload.execute();
                }
            } else {
                Log.w(TAG, "Unknown Activity Result from mediaImport: "
                        + resultCode);
            }
        }
    }

    private void logOut() {
        // Remove credentials from the session
        mApi.getSession().unlink();

        // Clear our stored keys
        clearKeys();
        // Change UI state to display logged out version
        setLoggedIn(false);
    }

    /**
     * Convenience function to change UI state based on being logged in
     */
    private void setLoggedIn(boolean loggedIn) {
        Log.i(TAG, "## loggedIn " + loggedIn);
        mLoggedIn = loggedIn;
        if (loggedIn) {
            mSubmit.setText("Unlink from Dropbox");
            mSubmit.setVisibility(View.GONE);
            mDisplay.setVisibility(View.VISIBLE);
            mSearch.setVisibility(View.VISIBLE);
            if (mMenuUnlinkDropBox != null) {
                mMenuUnlinkDropBox.setVisible(true);
            }
        } else {
            mSubmit.setText("Link with Dropbox");
            mSubmit.setVisibility(View.VISIBLE);
            mDisplay.setVisibility(View.GONE);
            mSearch.setVisibility(View.GONE);
            if (mMenuUnlinkDropBox != null) {
                mMenuUnlinkDropBox.setVisible(false);
            }
            //mImage.setImageDrawable(null);
        }
    }

    private void checkAppKeySetup() {
        // Check to make sure that we have a valid app key
        if (APP_KEY.startsWith("CHANGE") ||
                APP_SECRET.startsWith("CHANGE")) {
            showToast("You must apply for an app key and secret from developers.dropbox.com, and add them to the DBRoulette ap before trying it.");
            finish();
            return;
        }

        // Check if the app has set up its manifest properly.
        Intent testIntent = new Intent(Intent.ACTION_VIEW);
        String scheme = "db-" + APP_KEY;
        String uri = scheme + "://" + AuthActivity.AUTH_VERSION + "/test";
        testIntent.setData(Uri.parse(uri));
        PackageManager pm = getPackageManager();
        if (0 == pm.queryIntentActivities(testIntent, 0).size()) {
            showToast("URL scheme in your app's " +
                    "manifest is not set up correctly. You should have a " +
                    "com.dropbox.client2.android.AuthActivity with the " +
                    "scheme: " + scheme);
            finish();
        }
    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(this, msg, Toast.LENGTH_LONG);
        error.show();
    }

    /**
     * Shows keeping the access keys returned from Trusted Authenticator in a local
     * store, rather than storing user name & password, and re-authenticating each
     * time (which is not to be done, ever).
     */
    private void loadAuth(AndroidAuthSession session) {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        String key = prefs.getString(ACCESS_KEY_NAME, null);
        String secret = prefs.getString(ACCESS_SECRET_NAME, null);
        if (key == null || secret == null || key.length() == 0 || secret.length() == 0) return;

        if (key.equals("oauth2:")) {
            // If the key is set to "oauth2:", then we can assume the token is for OAuth 2.
            session.setOAuth2AccessToken(secret);
        } else {
            // Still support using old OAuth 1 tokens.
            session.setAccessTokenPair(new AccessTokenPair(key, secret));
        }
    }

    /**
     * Shows keeping the access keys returned from Trusted Authenticator in a local
     * store, rather than storing user name & password, and re-authenticating each
     * time (which is not to be done, ever).
     */
    private void storeAuth(AndroidAuthSession session) {
        // Store the OAuth 2 access token, if there is one.
        String oauth2AccessToken = session.getOAuth2AccessToken();
        if (oauth2AccessToken != null) {
            SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
            SharedPreferences.Editor edit = prefs.edit();
            edit.putString(ACCESS_KEY_NAME, "oauth2:");
            edit.putString(ACCESS_SECRET_NAME, oauth2AccessToken);
            edit.commit();
            return;
        }
        // Store the OAuth 1 access token, if there is one.  This is only necessary if
        // you're still using OAuth 1.
        AccessTokenPair oauth1AccessToken = session.getAccessTokenPair();
        if (oauth1AccessToken != null) {
            SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
            SharedPreferences.Editor edit = prefs.edit();
            edit.putString(ACCESS_KEY_NAME, oauth1AccessToken.key);
            edit.putString(ACCESS_SECRET_NAME, oauth1AccessToken.secret);
            edit.commit();
            return;
        }
    }

    private void clearKeys() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        SharedPreferences.Editor edit = prefs.edit();
        edit.clear();
        edit.commit();
    }

    private AndroidAuthSession buildSession() {
        AppKeyPair appKeyPair = new AppKeyPair(APP_KEY, APP_SECRET);

        AndroidAuthSession session = new AndroidAuthSession(appKeyPair);
        loadAuth(session);
        return session;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        mMenuUnlinkDropBox = menu.findItem(R.id.action_logout);
        mMenuUnlinkDropBox.setVisible(mLoggedIn);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        } else if (id == R.id.action_logout) {
            if (mLoggedIn) {
                logOut();
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
