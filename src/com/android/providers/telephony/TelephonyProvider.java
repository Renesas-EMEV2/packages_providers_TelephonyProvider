/* //device/content/providers/telephony/TelephonyProvider.java
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.providers.telephony;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.provider.Telephony;
import android.util.Log;
import android.util.Xml;

import com.android.internal.telephony.BaseCommands;
import com.android.internal.telephony.Phone;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;


public class TelephonyProvider extends ContentProvider
{
    private static final String DATABASE_NAME = "telephony.db";
    private static final boolean DBG = true;

    private static final int DATABASE_VERSION = 7 << 16;
    private static final int URL_TELEPHONY = 1;
    private static final int URL_CURRENT = 2;
    private static final int URL_ID = 3;
    private static final int URL_RESTOREAPN = 4;
    private static final int URL_PREFERAPN = 5;
    private static final int URL_PREFERAPN_NO_UPDATE = 6;

    private static final String TAG = "TelephonyProvider";
    private static final String CARRIERS_TABLE = "carriers";

    private static final String PREF_FILE = "preferred-apn";
    private static final String COLUMN_APN_ID = "apn_id";
    private static final String APN_CONFIG_CHECKSUM = "apn_conf_checksum";

    private static final String PARTNER_APNS_PATH = "etc/apns-conf.xml";

    private static final UriMatcher s_urlMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final ContentValues s_currentNullMap;
    private static final ContentValues s_currentSetMap;

    static {
        s_urlMatcher.addURI("telephony", "carriers", URL_TELEPHONY);
        s_urlMatcher.addURI("telephony", "carriers/current", URL_CURRENT);
        s_urlMatcher.addURI("telephony", "carriers/#", URL_ID);
        s_urlMatcher.addURI("telephony", "carriers/restore", URL_RESTOREAPN);
        s_urlMatcher.addURI("telephony", "carriers/preferapn", URL_PREFERAPN);
        s_urlMatcher.addURI("telephony", "carriers/preferapn_no_update", URL_PREFERAPN_NO_UPDATE);

        s_currentNullMap = new ContentValues(1);
        s_currentNullMap.put("current", (Long) null);

        s_currentSetMap = new ContentValues(1);
        s_currentSetMap.put("current", "1");
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        // Context to access resources with
        private Context mContext;

        /**
         * DatabaseHelper helper class for loading apns into a database.
         *
         * @param context of the user.
         */
        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, getVersion(context));
            mContext = context;
        }

        private static int getVersion(Context context) {
            // Get the database version, combining a static schema version and the XML version
            Resources r = context.getResources();
            XmlResourceParser parser = r.getXml(com.android.internal.R.xml.apns);
            try {
                XmlUtils.beginDocument(parser, "apns");
                int publicversion = Integer.parseInt(parser.getAttributeValue(null, "version"));
                return DATABASE_VERSION | publicversion;
            } catch (Exception e) {
                Log.e(TAG, "Can't get version of APN database", e);
                return DATABASE_VERSION;
            } finally {
                parser.close();
            }
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Set up the database schema
            db.execSQL("CREATE TABLE " + CARRIERS_TABLE +
                "(_id INTEGER PRIMARY KEY," +
                    "name TEXT," +
                    "numeric TEXT," +
                    "mcc TEXT," +
                    "mnc TEXT," +
                    "apn TEXT," +
                    "user TEXT," +
                    "server TEXT," +
                    "password TEXT," +
                    "proxy TEXT," +
                    "port TEXT," +
                    "mmsproxy TEXT," +
                    "mmsport TEXT," +
                    "mmsc TEXT," +
                    "authtype INTEGER," +
                    "type TEXT," +
                    "current INTEGER," +
                    "protocol TEXT," +
                    "roaming_protocol TEXT," +
                    "carrier_enabled BOOLEAN," +
                    "bearer INTEGER);");

            initDatabase(db);
        }

        private void initDatabase(SQLiteDatabase db) {
            // Read internal APNS data
            Resources r = mContext.getResources();
            XmlResourceParser parser = r.getXml(com.android.internal.R.xml.apns);
            int publicversion = -1;
            try {
                XmlUtils.beginDocument(parser, "apns");
                publicversion = Integer.parseInt(parser.getAttributeValue(null, "version"));
                loadApns(db, parser);
            } catch (Exception e) {
                Log.e(TAG, "Got exception while loading APN database.", e);
            } finally {
                parser.close();
            }

           // Read external APNS data (partner-provided)
            XmlPullParser confparser = null;
            // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
            File confFile = new File(Environment.getRootDirectory(), PARTNER_APNS_PATH);
            FileReader confreader = null;
            try {
                confreader = new FileReader(confFile);
                confparser = Xml.newPullParser();
                confparser.setInput(confreader);
                XmlUtils.beginDocument(confparser, "apns");

                // Sanity check. Force internal version and confidential versions to agree
                int confversion = Integer.parseInt(confparser.getAttributeValue(null, "version"));
                if (publicversion != confversion) {
                    throw new IllegalStateException("Internal APNS file version doesn't match "
                            + confFile.getAbsolutePath());
                }

                loadApns(db, confparser);
            } catch (FileNotFoundException e) {
                // It's ok if the file isn't found. It means there isn't a confidential file
                // Log.e(TAG, "File not found: '" + confFile.getAbsolutePath() + "'");
            } catch (Exception e) {
                Log.e(TAG, "Exception while parsing '" + confFile.getAbsolutePath() + "'", e);
            } finally {
                try { if (confreader != null) confreader.close(); } catch (IOException e) { }
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < (5 << 16 | 6)) {
                // 5 << 16 is the Database version and 6 in the xml version.

                // This change adds a new authtype column to the database.
                // The auth type column can have 4 values: 0 (None), 1 (PAP), 2 (CHAP)
                // 3 (PAP or CHAP). To avoid breaking compatibility, with already working
                // APNs, the unset value (-1) will be used. If the value is -1.
                // the authentication will default to 0 (if no user / password) is specified
                // or to 3. Currently, there have been no reported problems with
                // pre-configured APNs and hence it is set to -1 for them. Similarly,
                // if the user, has added a new APN, we set the authentication type
                // to -1.

                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN authtype INTEGER DEFAULT -1;");

                oldVersion = 5 << 16 | 6;
            }
            if (oldVersion < (6 << 16 | 6)) {
                // Add protcol fields to the APN. The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN protocol TEXT DEFAULT IP;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN roaming_protocol TEXT DEFAULT IP;");
                oldVersion = 6 << 16 | 6;
            }
            if (oldVersion < (7 << 16 | 6)) {
                // Add protcol fields to the APN. The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN carrier_enabled BOOLEAN DEFAULT 1;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN bearer INTEGER DEFAULT 0;");
                oldVersion = 7 << 16 | 6;
            }
        }

        /**
         * Gets the next row of apn values.
         *
         * @param parser the parser
         * @return the row or null if it's not an apn
         */
        private ContentValues getRow(XmlPullParser parser) {
            if (!"apn".equals(parser.getName())) {
                return null;
            }

            ContentValues map = new ContentValues();

            String mcc = parser.getAttributeValue(null, "mcc");
            String mnc = parser.getAttributeValue(null, "mnc");
            String numeric = mcc + mnc;

            map.put(Telephony.Carriers.NUMERIC,numeric);
            map.put(Telephony.Carriers.MCC, mcc);
            map.put(Telephony.Carriers.MNC, mnc);
            map.put(Telephony.Carriers.NAME, parser.getAttributeValue(null, "carrier"));
            map.put(Telephony.Carriers.APN, parser.getAttributeValue(null, "apn"));
            map.put(Telephony.Carriers.USER, parser.getAttributeValue(null, "user"));
            map.put(Telephony.Carriers.SERVER, parser.getAttributeValue(null, "server"));
            map.put(Telephony.Carriers.PASSWORD, parser.getAttributeValue(null, "password"));

            // do not add NULL to the map so that insert() will set the default value
            String proxy = parser.getAttributeValue(null, "proxy");
            if (proxy != null) {
                map.put(Telephony.Carriers.PROXY, proxy);
            }
            String port = parser.getAttributeValue(null, "port");
            if (port != null) {
                map.put(Telephony.Carriers.PORT, port);
            }
            String mmsproxy = parser.getAttributeValue(null, "mmsproxy");
            if (mmsproxy != null) {
                map.put(Telephony.Carriers.MMSPROXY, mmsproxy);
            }
            String mmsport = parser.getAttributeValue(null, "mmsport");
            if (mmsport != null) {
                map.put(Telephony.Carriers.MMSPORT, mmsport);
            }
            map.put(Telephony.Carriers.MMSC, parser.getAttributeValue(null, "mmsc"));
            String type = parser.getAttributeValue(null, "type");
            if (type != null) {
                map.put(Telephony.Carriers.TYPE, type);
            }

            String auth = parser.getAttributeValue(null, "authtype");
            if (auth != null) {
                map.put(Telephony.Carriers.AUTH_TYPE, Integer.parseInt(auth));
            }

            String protocol = parser.getAttributeValue(null, "protocol");
            if (protocol != null) {
                map.put(Telephony.Carriers.PROTOCOL, protocol);
            }

            String roamingProtocol = parser.getAttributeValue(null, "roaming_protocol");
            if (roamingProtocol != null) {
                map.put(Telephony.Carriers.ROAMING_PROTOCOL, roamingProtocol);
            }

            String carrierEnabled = parser.getAttributeValue(null, "carrier_enabled");
            if (carrierEnabled != null) {
                map.put(Telephony.Carriers.CARRIER_ENABLED, Boolean.parseBoolean(carrierEnabled));
            }

            String bearer = parser.getAttributeValue(null, "bearer");
            if (bearer != null) {
                map.put(Telephony.Carriers.BEARER, Integer.parseInt(bearer));
            }
            return map;
        }

        /*
         * Loads apns from xml file into the database
         *
         * @param db the sqlite database to write to
         * @param parser the xml parser
         *
         */
        private void loadApns(SQLiteDatabase db, XmlPullParser parser) {
            Log.d(TAG, "Entering loadApns...");
	    int nRows = 0;
            if (parser != null) {
                try {
                    while (true) {
                        XmlUtils.nextElement(parser);
                        ContentValues row = getRow(parser);
                        if (row != null) {
                            insertAddingDefaults(db, CARRIERS_TABLE, row);	
			    nRows++;
                        } else {
                            break;  // do we really want to skip the rest of the file?
                        }
                    }
                    Log.d(TAG, "Created " + nRows + " APN rows");
                } catch (XmlPullParserException e)  {
                    Log.e(TAG, "Got XmlPullParserException.", e);
                } catch (IOException e) {
                    Log.e(TAG, "Got IOException.", e);
                }
            }
        }

        private void insertAddingDefaults(SQLiteDatabase db, String table, ContentValues row) {
            // Initialize defaults if any
            if (row.containsKey(Telephony.Carriers.AUTH_TYPE) == false) {
                row.put(Telephony.Carriers.AUTH_TYPE, -1);
            }
            if (row.containsKey(Telephony.Carriers.PROTOCOL) == false) {
                row.put(Telephony.Carriers.PROTOCOL, "IP");
            }
            if (row.containsKey(Telephony.Carriers.ROAMING_PROTOCOL) == false) {
                row.put(Telephony.Carriers.ROAMING_PROTOCOL, "IP");
            }
            if (row.containsKey(Telephony.Carriers.CARRIER_ENABLED) == false) {
                row.put(Telephony.Carriers.CARRIER_ENABLED, true);
            }
            if (row.containsKey(Telephony.Carriers.BEARER) == false) {
                row.put(Telephony.Carriers.BEARER, 0);
            }
            db.insert(CARRIERS_TABLE, null, row);
        }
    }

    @Override
    public boolean onCreate() {
        long oldCheckSum = getAPNConfigCheckSum();
        File confFile = new File(Environment.getRootDirectory(), PARTNER_APNS_PATH);
        long newCheckSum = -1L;

        if (DBG) {
            Log.w(TAG, "onCreate: confFile=" + confFile.getAbsolutePath() +
                    " oldCheckSum=" + oldCheckSum);
        }
        mOpenHelper = new DatabaseHelper(getContext());

        if (isLteOnCdma()) {
            // Check to see if apns-conf.xml file changed. If so, generate db again.
            //
            // TODO: Generalize so we can handle apns-conf.xml updates
            // and preserve any modifications the user might make. For
            // now its safe on LteOnCdma devices because the user cannot
            // make changes.
            try {
                newCheckSum = FileUtils.checksumCrc32(confFile);
                if (DBG) Log.w(TAG, "onCreate: newCheckSum=" + newCheckSum);
                if (oldCheckSum != newCheckSum) {
                    Log.w(TAG, "Rebuilding Telephony.db");
                    restoreDefaultAPN();
                    setAPNConfigCheckSum(newCheckSum);
                }
            } catch (FileNotFoundException e) {
                Log.e(TAG, "FileNotFoundException: '" + confFile.getAbsolutePath() + "'", e);
            } catch (IOException e) {
                Log.e(TAG, "IOException: '" + confFile.getAbsolutePath() + "'", e);
            }
        }
        return true;
    }

    private boolean isLteOnCdma() {
        return true;//BaseCommands.getLteOnCdmaModeStatic() == Phone.LTE_ON_CDMA_TRUE;
    }

    private void setPreferredApnId(Long id) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putLong(COLUMN_APN_ID, id != null ? id.longValue() : -1);
        editor.apply();
    }

    private long getPreferredApnId() {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        return sp.getLong(COLUMN_APN_ID, -1);
    }

    private long getAPNConfigCheckSum() {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        return sp.getLong(APN_CONFIG_CHECKSUM, -1);
    }

    private void setAPNConfigCheckSum(long id) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putLong(APN_CONFIG_CHECKSUM, id);
        editor.apply();
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables("carriers");

        int match = s_urlMatcher.match(url);
        switch (match) {
            // do nothing
            case URL_TELEPHONY: {
                break;
            }


            case URL_CURRENT: {
                qb.appendWhere("current IS NOT NULL");
                // do not ignore the selection since MMS may use it.
                //selection = null;
                break;
            }

            case URL_ID: {
                qb.appendWhere("_id = " + url.getPathSegments().get(1));
                break;
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE: {
                qb.appendWhere("_id = " + getPreferredApnId());
                break;
            }

            default: {
                return null;
            }
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = qb.query(db, projectionIn, selection, selectionArgs, null, null, sort);
        ret.setNotificationUri(getContext().getContentResolver(), url);
        return ret;
    }

    @Override
    public String getType(Uri url)
    {
        switch (s_urlMatcher.match(url)) {
        case URL_TELEPHONY:
            return "vnd.android.cursor.dir/telephony-carrier";

        case URL_ID:
            return "vnd.android.cursor.item/telephony-carrier";

        case URL_PREFERAPN:
        case URL_PREFERAPN_NO_UPDATE:
            return "vnd.android.cursor.item/telephony-carrier";

        default:
            throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues)
    {
        Uri result = null;

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        boolean notify = false;
        switch (match)
        {
            case URL_TELEPHONY:
            {
                ContentValues values;
                if (initialValues != null) {
                    values = new ContentValues(initialValues);
                } else {
                    values = new ContentValues();
                }

                // TODO Review this. This code should probably not bet here.
                // It is valid for the database to return a null string.
                if (!values.containsKey(Telephony.Carriers.NAME)) {
                    values.put(Telephony.Carriers.NAME, "");
                }
                if (!values.containsKey(Telephony.Carriers.APN)) {
                    values.put(Telephony.Carriers.APN, "");
                }
                if (!values.containsKey(Telephony.Carriers.PORT)) {
                    values.put(Telephony.Carriers.PORT, "");
                }
                if (!values.containsKey(Telephony.Carriers.PROXY)) {
                    values.put(Telephony.Carriers.PROXY, "");
                }
                if (!values.containsKey(Telephony.Carriers.USER)) {
                    values.put(Telephony.Carriers.USER, "");
                }
                if (!values.containsKey(Telephony.Carriers.SERVER)) {
                    values.put(Telephony.Carriers.SERVER, "");
                }
                if (!values.containsKey(Telephony.Carriers.PASSWORD)) {
                    values.put(Telephony.Carriers.PASSWORD, "");
                }
                if (!values.containsKey(Telephony.Carriers.MMSPORT)) {
                    values.put(Telephony.Carriers.MMSPORT, "");
                }
                if (!values.containsKey(Telephony.Carriers.MMSPROXY)) {
                    values.put(Telephony.Carriers.MMSPROXY, "");
                }
                if (!values.containsKey(Telephony.Carriers.AUTH_TYPE)) {
                    values.put(Telephony.Carriers.AUTH_TYPE, -1);
                }
                if (!values.containsKey(Telephony.Carriers.PROTOCOL)) {
                    values.put(Telephony.Carriers.PROTOCOL, "IP");
                }
                if (!values.containsKey(Telephony.Carriers.ROAMING_PROTOCOL)) {
                    values.put(Telephony.Carriers.ROAMING_PROTOCOL, "IP");
                }
                if (!values.containsKey(Telephony.Carriers.CARRIER_ENABLED)) {
                    values.put(Telephony.Carriers.CARRIER_ENABLED, true);
                }
                if (!values.containsKey(Telephony.Carriers.BEARER)) {
                    values.put(Telephony.Carriers.BEARER, 0);
                }

                long rowID = db.insert(CARRIERS_TABLE, null, values);
                if (rowID > 0)
                {
                    result = ContentUris.withAppendedId(Telephony.Carriers.CONTENT_URI, rowID);
                    notify = true;
                }

                if (false) Log.d(TAG, "inserted " + values.toString() + " rowID = " + rowID);
                break;
            }

            case URL_CURRENT:
            {
                // null out the previous operator
                db.update("carriers", s_currentNullMap, "current IS NOT NULL", null);

                String numeric = initialValues.getAsString("numeric");
                int updated = db.update("carriers", s_currentSetMap,
                        "numeric = '" + numeric + "'", null);

                if (updated > 0)
                {
                    if (false) {
                        Log.d(TAG, "Setting numeric '" + numeric + "' to be the current operator");
                    }
                }
                else
                {
                    Log.e(TAG, "Failed setting numeric '" + numeric + "' to the current operator");
                }
                break;
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                if (initialValues != null) {
                    if(initialValues.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(initialValues.getAsLong(COLUMN_APN_ID));
                    }
                }
                break;
            }
        }

        if (notify) {
            getContext().getContentResolver().notifyChange(Telephony.Carriers.CONTENT_URI, null);
        }

        return result;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs)
    {
        int count = 0;

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY:
            {
                count = db.delete(CARRIERS_TABLE, where, whereArgs);
                break;
            }

            case URL_CURRENT:
            {
                count = db.delete(CARRIERS_TABLE, where, whereArgs);
                break;
            }

            case URL_ID:
            {
                count = db.delete(CARRIERS_TABLE, Telephony.Carriers._ID + "=?",
                        new String[] { url.getLastPathSegment() });
                break;
            }

            case URL_RESTOREAPN: {
                count = 1;
                restoreDefaultAPN();
                break;
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                setPreferredApnId((long)-1);
                if (match == URL_PREFERAPN) count = 1;
                break;
            }

            default: {
                throw new UnsupportedOperationException("Cannot delete that URL: " + url);
            }
        }

        if (count > 0) {
            getContext().getContentResolver().notifyChange(Telephony.Carriers.CONTENT_URI, null);
        }

        return count;
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs)
    {
        int count = 0;

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY:
            {
                count = db.update(CARRIERS_TABLE, values, where, whereArgs);
                break;
            }

            case URL_CURRENT:
            {
                count = db.update(CARRIERS_TABLE, values, where, whereArgs);
                break;
            }

            case URL_ID:
            {
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URL " + url + " with a where clause");
                }
                count = db.update(CARRIERS_TABLE, values, Telephony.Carriers._ID + "=?",
                        new String[] { url.getLastPathSegment() });
                break;
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                if (values != null) {
                    if (values.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(values.getAsLong(COLUMN_APN_ID));
                        if (match == URL_PREFERAPN) count = 1;
                    }
                }
                break;
            }

            default: {
                throw new UnsupportedOperationException("Cannot update that URL: " + url);
            }
        }

        if (count > 0) {
            getContext().getContentResolver().notifyChange(Telephony.Carriers.CONTENT_URI, null);
        }

        return count;
    }

    private void checkPermission() {
        // Check the permissions
        getContext().enforceCallingOrSelfPermission("android.permission.WRITE_APN_SETTINGS",
                "No permission to write APN settings");
    }

    private DatabaseHelper mOpenHelper;

    private void restoreDefaultAPN() {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        db.delete(CARRIERS_TABLE, null, null);
        setPreferredApnId((long)-1);
        mOpenHelper.initDatabase(db);
    }
}
