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

import ezvcard.VCard;
import ezvcard.io.text.VCardReader;
import ezvcard.parameter.RelatedType;
import ezvcard.parameter.TelephoneType;
import ezvcard.property.Categories;
import ezvcard.property.FormattedName;
import ezvcard.property.Related;
import ezvcard.property.Telephone;

/**
 * Created by baishan on 13/01/16.
 */
public class SearchPlaces extends AsyncTask<Void, Long, Boolean> {


    private static final String TAG = "SearchPlaces";
    private final String mSearchText;
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
    private String mVcardContents;

    public SearchPlaces(Context context, DropboxAPI<?> api,
                        String dropboxPath, LinearLayout view, String searchText) {
        // We set the context this way so we don't accidentally leak activities
        mContext = context;

        mApi = api;
        mPath = dropboxPath;
        mSearchResultView = view;
        mSearchText = searchText;

        mDialog = new ProgressDialog(context);
        mDialog.setMessage("Searching");
        mDialog.show();
    }

    @Override
    protected Boolean doInBackground(Void... params) {
        mVcardContents = "";
        try {
            // Get the metadata for a directory
            DropboxAPI.Entry dirent = mApi.metadata(mPath, 1000, null, true, null);

            if (!dirent.isDir || dirent.contents == null) {
                // It's not a directory, or there's nothing in it
                mErrorMsg = "File or empty directory";
                return false;
            }

            for (DropboxAPI.Entry ent : dirent.contents) {
                Log.i("SearchPlaces", "## path " + ent.path + " file name" + ent.fileName());
                if (ent.fileName().endsWith(".vcf")) {
                    String content = readVcard(mApi.getFileStream(ent.path, ent.rev));
                    if (content != null) {
                        mVcardContents += readVcard(mApi.getFileStream(ent.path, ent.rev));
                    }
                }
            }

            return true;

        } catch (DropboxUnlinkedException e) {
            // The AuthSession wasn't properly authenticated or user unlinked.
        } catch (DropboxPartialFileException e) {
            // We canceled the operation
            mErrorMsg = "Download canceled";
        } catch (DropboxServerException e) {
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
            if (mVcardContents != null && mVcardContents.length() > 0) {
                Log.i("SearchPlaces", "## mVcardContents " + mVcardContents);

                mSearchResultView.removeAllViews();
                ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);

                TextView txtContent = new TextView(mContext);
                txtContent.setLayoutParams(params);
                txtContent.setText(mVcardContents);
                mSearchResultView.addView(txtContent);
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

    /**
     * VCARD stuff
     */
    private String readVcard(InputStream vcardStream) {
        String content = null;
        List<VCard> allVcards = new ArrayList<VCard>();
        VCardReader reader = new VCardReader(vcardStream);
        try {
            VCard vcard;
            while ((vcard = reader.readNext()) != null) {
                allVcards.add(vcard);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //String searchText = "NLSN";
        VCard vcardFound = null;
        for (VCard vcard : allVcards) {
            if (searchCard(vcard, mSearchText)) {
                vcardFound = vcard;
            }
        }

        if (vcardFound != null) {
            content = toVcardString(vcardFound);
            Log.i(TAG, "##content " + content);
            //3. related
            for (Related related : vcardFound.getRelations()) {
                String typeValue = "";
                int index = 0;
                for (RelatedType type : related.getTypes()) {
                    if (index++ > 0) {
                        typeValue += ", ";
                    }
                    typeValue += type.getValue();
                }
                VCard relatedCard = findVcardByUri(related.getUri().trim(), allVcards);
                if (relatedCard != null) {
                    if (!"".equals(typeValue)) {
                        content += "\nType: " + typeValue;
                    }
                    content += toVcardString(relatedCard);
                } else {
                    //log("relatedCard " + relatedCard);
                }
            }
            //log(content);
        }

        return content;
    }

    /**
     * search vcard see if contain certain text
     *
     * @param vcard
     * @param searchText
     * @return
     */
    private boolean searchCard(VCard vcard, String searchText) {
        searchText = searchText.toLowerCase();
        //1. name
        FormattedName fn = vcard.getFormattedName();
        if (fn != null) {
            String name = fn.getValue();
            if (name.toLowerCase().contains(searchText)) {
                return true;
            }
        }

        //2. categories
        Categories categories = vcard.getCategories();
        //log("categories " + categories);
        if (categories != null) {
            for (Object value : categories.getValues()) {
                if (value.toString().toLowerCase().contains(searchText)) {
                    return true;
                }
            }
        }

        return false;
    }

    private String toVcardString(VCard vcard) {
        StringBuffer content = new StringBuffer();
        //1. name
        FormattedName fn = vcard.getFormattedName();
        if (fn != null) {
            content.append("\nName: ").append(fn.getValue());
        }
        String name = (fn == null) ? null : fn.getValue();
        //2. categories
        Categories categories = vcard.getCategories();
        if (categories != null) {
            content.append("\nCategories: ");
            int index = 0;
            for (Object value : categories.getValues()) {
                if (index++ > 0) {
                    content.append(", ");
                }
                content.append(value);
            }
        }

        //3. phone
        List<Telephone> phones = vcard.getTelephoneNumbers();
        for (Telephone phone : phones) {
            String types = "";
            int index = 0;
            for (TelephoneType telephoneType : phone.getTypes()) {
                if (index++ > 0) {
                    types += ", ";
                }
                types += telephoneType.getValue();
            }
            if (types != null) {
                content.append("\n").append(types);
            }
            content.append(phone.getText());
        }

        return content.toString();
    }

    private VCard findVcardByUri(String uri, List<VCard> allVcards) {
        for (VCard vcard : allVcards) {
            if (uri.equalsIgnoreCase(vcard.getUid().getValue().trim())) {
                return vcard;
            }
        }
        return null;
    }

}
