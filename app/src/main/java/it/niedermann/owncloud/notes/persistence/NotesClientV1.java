package it.niedermann.owncloud.notes.persistence;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.nextcloud.android.sso.model.SingleSignOnAccount;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import it.niedermann.owncloud.notes.persistence.entity.Note;
import it.niedermann.owncloud.notes.shared.model.ServerResponse.NoteResponse;
import it.niedermann.owncloud.notes.shared.model.ServerResponse.NotesResponse;
import it.niedermann.owncloud.notes.shared.model.ServerSettings;

@WorkerThread
public class NotesClientV1 extends NotesClient {

    private static final String API_PATH = "/index.php/apps/notes/api/v1/";

    NotesClientV1(@NonNull Context appContext) {
        super(appContext);
    }

    NotesResponse getNotes(SingleSignOnAccount ssoAccount, Calendar lastModified, String lastETag) throws Exception {
        final Map<String, String> parameter = new HashMap<>();
        parameter.put(GET_PARAM_KEY_PRUNE_BEFORE, Long.toString(lastModified == null ? 0 : lastModified.getTimeInMillis() / 1_000));
        return new NotesResponse(requestServer(ssoAccount, "notes", METHOD_GET, parameter, null, lastETag));
    }

    private NoteResponse putNote(SingleSignOnAccount ssoAccount, Note note, String path, String method) throws Exception {
        final JSONObject paramObject = new JSONObject();
        paramObject.accumulate(JSON_TITLE, note.getTitle());
        paramObject.accumulate(JSON_CONTENT, note.getContent());
        paramObject.accumulate(JSON_MODIFIED, note.getModified().getTimeInMillis() / 1_000);
        paramObject.accumulate(JSON_FAVORITE, note.getFavorite());
        paramObject.accumulate(JSON_CATEGORY, note.getCategory());
        return new NoteResponse(requestServer(ssoAccount, path, method, null, paramObject, null));
    }

    @Override
    NoteResponse createNote(SingleSignOnAccount ssoAccount, Note note) throws Exception {
        return putNote(ssoAccount, note, "notes", METHOD_POST);
    }

    @Override
    NoteResponse editNote(SingleSignOnAccount ssoAccount, Note note) throws Exception {
        return putNote(ssoAccount, note, "notes/" + note.getRemoteId(), METHOD_PUT);
    }

    @Override
    void deleteNote(SingleSignOnAccount ssoAccount, long noteId) throws Exception {
        this.requestServer(ssoAccount, "notes/" + noteId, METHOD_DELETE, null, null, null);
    }

    @Override
    protected String getApiPath() {
        return API_PATH;
    }

    @Override
    public ServerSettings getServerSettings(SingleSignOnAccount ssoAccount) throws Exception {
        return ServerSettings.from(new JSONObject(this.requestServer(ssoAccount, "settings", METHOD_GET, null, null, null).getContent()));
    }

    @Override
    public ServerSettings putServerSettings(SingleSignOnAccount ssoAccount, @NonNull ServerSettings settings) throws Exception {
        final JSONObject paramObject = new JSONObject();
        paramObject.accumulate(JSON_SETTINGS_NOTES_PATH, settings.getNotesPath());
        paramObject.accumulate(JSON_SETTINGS_FILE_SUFFIX, settings.getFileSuffix());
        return ServerSettings.from(new JSONObject(this.requestServer(ssoAccount, "settings", METHOD_PUT, null, paramObject, null).getContent()));
    }
}
