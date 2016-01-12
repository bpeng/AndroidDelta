package nz.org.geonet.delta;

import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.exception.DropboxException;
import com.dropbox.client2.exception.DropboxIOException;
import com.dropbox.client2.exception.DropboxParseException;
import com.dropbox.client2.exception.DropboxPartialFileException;
import com.dropbox.client2.exception.DropboxServerException;
import com.dropbox.client2.exception.DropboxUnlinkedException;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by baishan on 13/01/16.
 */
public class SearchPlaces extends AsyncTask<Void, Long, Boolean> {


    private Context mContext;
    private final ProgressDialog mDialog;
    private DropboxAPI<?> mApi;
    private String mPath;
    private LinearLayout mSearchResultView;
    private Drawable mDrawable;

    private FileOutputStream mFos;

    //private boolean mCanceled;
    private Long mFileLen;
    private String mErrorMsg;
    private List<String> mVcardContents;

    // Note that, since we use a single file name here for simplicity, you
    // won't be able to use this code for two simultaneous downloads.
    private final static String IMAGE_FILE_NAME = "dbroulette.png";

    public SearchPlaces(Context context, DropboxAPI<?> api,
                        String dropboxPath, LinearLayout view) {
        // We set the context this way so we don't accidentally leak activities
        mContext = context;

        mApi = api;
        mPath = dropboxPath;
        mSearchResultView = view;

        mDialog = new ProgressDialog(context);
        mDialog.setMessage("Searching");
        mDialog.show();
    }

    @Override
    protected Boolean doInBackground(Void... params) {
        try {
            // Get the metadata for a directory
            DropboxAPI.Entry dirent = mApi.metadata(mPath, 1000, null, true, null);

            if (!dirent.isDir || dirent.contents == null) {
                // It's not a directory, or there's nothing in it
                mErrorMsg = "File or empty directory";
                return false;
            }

            // Make a list of everything in it that we can get a thumbnail for
            ArrayList<DropboxAPI.Entry> vcardFiles = new ArrayList<DropboxAPI.Entry>();
            if (mVcardContents == null) {
                mVcardContents = new ArrayList<String>();
            } else {
                mVcardContents.clear();
            }
            for (DropboxAPI.Entry ent : dirent.contents) {
                Log.i("SearchPlaces", "## path " + ent.path + " file name" + ent.fileName());
                if (ent.fileName().endsWith(".vcf")) {
                    // Add it to the list of thumbs we can choose from
                    // vcardFiles.add(ent);
                    String content = fromStream(mApi.getFileStream(ent.path, ent.rev));
                    //Log.i("SearchPlaces", "## content " + content);
                    mVcardContents.add(content);
                }
            }

            return true;

        } catch (DropboxUnlinkedException e) {
            // The AuthSession wasn't properly authenticated or user unlinked.
        } catch (DropboxPartialFileException e) {
            // We canceled the operation
            mErrorMsg = "Download canceled";
        } catch (DropboxServerException e) {
            // Server-side exception.  These are examples of what could happen,
            // but we don't do anything special with them here.
            if (e.error == DropboxServerException._304_NOT_MODIFIED) {
                // won't happen since we don't pass in revision with metadata
            } else if (e.error == DropboxServerException._401_UNAUTHORIZED) {
                // Unauthorized, so we should unlink them.  You may want to
                // automatically log the user out in this case.
            } else if (e.error == DropboxServerException._403_FORBIDDEN) {
                // Not allowed to access this
            } else if (e.error == DropboxServerException._404_NOT_FOUND) {
                // path not found (or if it was the thumbnail, can't be
                // thumbnailed)
            } else if (e.error == DropboxServerException._406_NOT_ACCEPTABLE) {
                // too many entries to return
            } else if (e.error == DropboxServerException._415_UNSUPPORTED_MEDIA) {
                // can't be thumbnailed
            } else if (e.error == DropboxServerException._507_INSUFFICIENT_STORAGE) {
                // user is over quota
            } else {
                // Something else
            }
            // This gets the Dropbox error, translated into the user's language
            mErrorMsg = e.body.userError;
            if (mErrorMsg == null) {
                mErrorMsg = e.body.error;
            }
        } catch (DropboxIOException e) {
            // Happens all the time, probably want to retry automatically.
            mErrorMsg = "Network error.  Try again.";
        } catch (DropboxParseException e) {
            // Probably due to Dropbox server restarting, should retry
            mErrorMsg = "Dropbox error.  Try again.";
        } catch (DropboxException e) {
            // Unknown error
            mErrorMsg = "Unknown error.  Try again.";
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static String fromStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder out = new StringBuilder();
        String newLine = System.getProperty("line.separator");
        String line;
        while ((line = reader.readLine()) != null) {
            out.append(line);
            out.append(newLine);
        }
        in.close();
        return out.toString();
    }

    @Override
    protected void onProgressUpdate(Long... progress) {
        int percent = (int) (100.0 * (double) progress[0] / mFileLen + 0.5);
        mDialog.setProgress(percent);
    }

    @Override
    protected void onPostExecute(Boolean result) {
        mDialog.dismiss();
        if (result) {
            // Set the image now that we have it
            //mSearchResultView.setImageDrawable(mDrawable);
            if (mVcardContents != null && mVcardContents.size() > 0) {
                Log.i("SearchPlaces", "## mVcardContents " + mVcardContents.size());

                mSearchResultView.removeAllViews();
                ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);

                for (String content : mVcardContents) {
                    TextView txtContent = new TextView(mContext);
                    txtContent.setLayoutParams(params);
                    txtContent.setText(content);
                    Log.i("SearchPlaces", "## content " + content);
                    mSearchResultView.addView(txtContent);
                }
            }
        } else {
            // Couldn't download it, so show an error
            showToast(mErrorMsg);
        }
    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        error.show();
    }
}
