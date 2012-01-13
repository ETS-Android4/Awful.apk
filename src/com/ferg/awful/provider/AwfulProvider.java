/********************************************************************************
 * Copyright (c) 2011, Scott Ferguson
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the software nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY SCOTT FERGUSON ''AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL SCOTT FERGUSON BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/

package com.ferg.awful.provider;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.ferg.awful.constants.Constants;
import com.ferg.awful.thread.*;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;

public class AwfulProvider extends ContentProvider {
    private static final String TAG = "AwfulProvider";

    private static final String DATABASE_NAME = "awful.db";
    private static final int DATABASE_VERSION = 11;

    public static final String TABLE_FORUM    = "forum";
    public static final String TABLE_THREADS    = "threads";
    public static final String TABLE_UCP_THREADS    = "ucp_thread";
    public static final String TABLE_POSTS    = "posts";
    public static final String TABLE_EMOTES    = "emotes";
    public static final String TABLE_CATEGORY    = "threadtags";
    public static final String TABLE_PM    = "private_messages";

    private static final int FORUM     = 0;
    private static final int FORUM_ID  = 1;
    private static final int POST      = 2;
    private static final int POST_ID   = 3;
    private static final int THREAD    = 4;
    private static final int THREAD_ID = 5;
    private static final int UCP_THREAD    = 6;
    private static final int UCP_THREAD_ID = 7;
    private static final int PM    = 8;
    private static final int PM_ID = 9;

    private static final UriMatcher sUriMatcher;
	private static HashMap<String, String> sForumProjectionMap;
	private static HashMap<String, String> sThreadProjectionMap;
	private static HashMap<String, String> sPostProjectionMap;
	private static HashMap<String, String> sUCPThreadProjectionMap;
	
	public static final String[] ThreadProjection = new String[]{AwfulThread.ID,
		AwfulThread.FORUM_ID,
		AwfulThread.INDEX,
		AwfulThread.TITLE,
		AwfulThread.POSTCOUNT,
		AwfulThread.UNREADCOUNT,
		AwfulThread.AUTHOR,
		AwfulThread.AUTHOR_ID,
		AwfulThread.LOCKED,
		AwfulThread.BOOKMARKED,
		AwfulThread.STICKY,
		AwfulThread.CATEGORY,
		AwfulThread.LASTPOSTER };

	public static final String[] ForumProjection = new String[]{
		AwfulForum.ID,
		AwfulForum.PARENT_ID,
		AwfulForum.INDEX,
		AwfulForum.TITLE,
		AwfulForum.SUBTEXT,
		AwfulForum.PAGE_COUNT
	};

    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context aContext) {
            super(aContext, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase aDb) {
            aDb.execSQL("CREATE TABLE " + TABLE_FORUM + " (" +
                AwfulForum.ID      + " INTEGER UNIQUE," + 
                AwfulForum.PARENT_ID      + " INTEGER," + //subforums list parent forum id, primary forums list 0 (index)
                AwfulForum.INDEX      + " INTEGER,"   	 + 
                AwfulForum.TITLE   + " VARCHAR,"        + 
                AwfulForum.SUBTEXT + " VARCHAR,"        + 
                AwfulForum.PAGE_COUNT + " INTEGER);");
            
            aDb.execSQL("CREATE TABLE " + TABLE_THREADS + " ("    +
                AwfulThread.ID      + " INTEGER UNIQUE,"  + 
                AwfulThread.FORUM_ID      + " INTEGER,"   + 
                AwfulThread.INDEX      + " INTEGER,"   	  + 
                AwfulThread.TITLE   + " VARCHAR,"         + 
                AwfulThread.POSTCOUNT   + " INTEGER,"     + 
                AwfulThread.UNREADCOUNT   + " INTEGER,"   + 
                AwfulThread.AUTHOR 		 + " VARCHAR,"    + 
                AwfulThread.AUTHOR_ID 		+ " INTEGER," +
                AwfulThread.LOCKED   	+ " INTEGER,"     + 
                AwfulThread.BOOKMARKED 	    + " INTEGER," +
                AwfulThread.STICKY   		+ " INTEGER," +
                AwfulThread.CATEGORY   		+ " INTEGER," +
            	AwfulThread.LASTPOSTER   + " VARCHAR);");
            
            aDb.execSQL("CREATE TABLE " + TABLE_UCP_THREADS + " ("    +
                AwfulThread.ID      + " INTEGER UNIQUE,"  + //to be joined with thread table
                AwfulThread.INDEX      + " INTEGER);");

            aDb.execSQL("CREATE TABLE " + TABLE_POSTS + " (" +
                AwfulPost.ID                    + " INTEGER UNIQUE," + 
                AwfulPost.THREAD_ID             + " INTEGER,"        + 
                AwfulPost.POST_INDEX            + " INTEGER,"        + 
                AwfulPost.DATE                  + " VARCHAR,"        + 
                AwfulPost.USER_ID               + " VARCHAR,"        + 
                AwfulPost.USERNAME              + " VARCHAR,"        +
                AwfulPost.PREVIOUSLY_READ       + " INTEGER,"        +
                AwfulPost.LAST_READ_URL         + " VARCHAR,"        +
                AwfulPost.EDITABLE              + " INTEGER,"        +
                AwfulPost.IS_OP                 + " INTEGER,"        +
                AwfulPost.IS_ADMIN              + " INTEGER,"        +
                AwfulPost.IS_MOD                + " INTEGER,"        +
                AwfulPost.AVATAR                + " VARCHAR,"        + 
                AwfulPost.CONTENT               + " VARCHAR,"        + 
                AwfulPost.EDITED                + " VARCHAR);");
            
            aDb.execSQL("CREATE TABLE " + TABLE_EMOTES + " ("    +
        		AwfulEmote.ID      	 + " INTEGER UNIQUE,"  + 
        		AwfulEmote.TEXT      + " VARCHAR UNIQUE,"   + 
                AwfulEmote.SUBTEXT   + " VARCHAR,"         + 
                AwfulEmote.URL   	 + " VARCHAR,"     + 
                AwfulEmote.CACHEFILE + " VARCHAR);");
            
            aDb.execSQL("CREATE TABLE " + TABLE_CATEGORY + " ("    +
                AwfulThread.ID      	 + " INTEGER UNIQUE,"  + 
                AwfulThread.TAG_URL      + " VARCHAR UNIQUE,"   + 
                AwfulThread.TAG_CACHEFILE + " VARCHAR);");
            
            aDb.execSQL("CREATE TABLE " + TABLE_PM + " ("    +
                    AwfulMessage.ID      	 + " INTEGER UNIQUE,"  + 
                    AwfulMessage.TITLE      + " VARCHAR,"   + 
                    AwfulMessage.AUTHOR      + " VARCHAR,"   + 
                    AwfulMessage.CONTENT      + " VARCHAR,"   + 
                    AwfulMessage.DATE + " VARCHAR);");

        }
        
        
        @Override
        public void onUpgrade(SQLiteDatabase aDb, int aOldVersion, int aNewVersion) {
            aDb.execSQL("DROP TABLE IF EXISTS " + TABLE_FORUM);
            aDb.execSQL("DROP TABLE IF EXISTS " + TABLE_THREADS);
            aDb.execSQL("DROP TABLE IF EXISTS " + TABLE_POSTS);
            aDb.execSQL("DROP TABLE IF EXISTS " + TABLE_EMOTES);
            aDb.execSQL("DROP TABLE IF EXISTS " + TABLE_CATEGORY);
            aDb.execSQL("DROP TABLE IF EXISTS " + TABLE_UCP_THREADS);
            aDb.execSQL("DROP TABLE IF EXISTS " + TABLE_PM);

            onCreate(aDb);
        }
    }

    private DatabaseHelper mDbHelper;

    @Override
    public boolean onCreate() {
        mDbHelper = new DatabaseHelper(getContext());

        return true;
    }

    @Override
    public String getType(Uri aUri) {
        return null;
    }

    @Override
    public int delete(Uri aUri, String aWhere, String[] aWhereArgs) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        String table = null;

		// Be careful when using this! A generic URI will delete all rows in the
		// table.  We want to do this before syncing so that we can easily throw
		// out old data.
        final int match = sUriMatcher.match(aUri);
        switch (match) {
            case FORUM:
                table = TABLE_FORUM;
                break;
            case POST:
                table = TABLE_POSTS;
                break;
            case THREAD:
                table = TABLE_THREADS;
                break;
            case UCP_THREAD:
                table = TABLE_UCP_THREADS;
                break;
            default:
                break;
        }

        return db.delete(table, aWhere, aWhereArgs);
    }

    @Override
    public int update(Uri aUri, ContentValues aValues, String aWhere, String[] aWhereArgs) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        String table = null;

        final int match = sUriMatcher.match(aUri);
        switch (match) {
            case FORUM_ID:
                aWhereArgs = insertSelectionArg(aWhereArgs, aUri.getLastPathSegment());        
                aWhere = AwfulForum.ID + "=?";
            case FORUM:
                table = TABLE_FORUM;
                break;
            case POST_ID:
                aWhereArgs = insertSelectionArg(aWhereArgs, aUri.getLastPathSegment());        
                aWhere = AwfulPost.ID + "=?";
            case POST:
                table = TABLE_POSTS;
                break;
            case THREAD_ID:
                aWhereArgs = insertSelectionArg(aWhereArgs, aUri.getLastPathSegment());        
                aWhere = AwfulThread.ID + "=?";
            case THREAD:
                table = TABLE_THREADS;
                break;
            case UCP_THREAD_ID:
                aWhereArgs = insertSelectionArg(aWhereArgs, aUri.getLastPathSegment());        
                aWhere = AwfulThread.ID + "=?";
            case UCP_THREAD:
                table = TABLE_UCP_THREADS;
                break;
        }

        int result = db.update(table, aValues, aWhere, aWhereArgs);

		getContext().getContentResolver().notifyChange(aUri, null);

		return result;
    }

	@Override
	public int bulkInsert(Uri aUri, ContentValues[] aValues) {
        String table = null;
		int result = 0;

        SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final int match = sUriMatcher.match(aUri);
        switch(match) {
            case FORUM:
                table = TABLE_FORUM;
                break;
            case POST:
                table = TABLE_POSTS;
                break;
            case THREAD:
                table = TABLE_THREADS;
                break;
            case UCP_THREAD:
                table = TABLE_UCP_THREADS;
                break;
        }

		db.beginTransaction();

		try {
			for (ContentValues value : aValues) {
				
				if(Build.VERSION.SDK_INT>7){
					db.insertWithOnConflict(table, "", value, SQLiteDatabase.CONFLICT_REPLACE);
				}else{
					StringBuilder column = new StringBuilder();
					StringBuilder valQuestions = new StringBuilder();
					Set<Entry<String, Object>> valSet = value.valueSet();
					String[] values = new String[valSet.size()];
					int ix=0;
					for(Entry<String, Object> entry : valSet){
						values[ix] = entry.getValue().toString();
						column.append(entry.getKey()+", ");
						valQuestions.append("?,");
					}
					db.rawQuery("INSERT OR REPLACE INTO "+table+" ("+column.substring(0, column.length()-2)+")", values);
				}
				result++;
			}

			db.setTransactionSuccessful();

            if (result > 0) {
                getContext().getContentResolver().notifyChange(aUri, null);
            }
		} catch (SQLiteConstraintException e) {
			Log.i(TAG, e.toString());
		} finally {
			db.endTransaction();
		}

		return result;
	}

    @Override
    public Uri insert(Uri aUri, ContentValues aValues) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        String table = null;

        final int match = sUriMatcher.match(aUri);
        switch(match) {
			case FORUM:
				table = TABLE_FORUM;
				break;
			case POST:
				table = TABLE_POSTS;
				break;
			case THREAD:
				table = TABLE_THREADS;
				break;
        }

        long rowId = db.insert(table, "", aValues); 
        
        if (rowId > -1) {
            Uri rowUri = ContentUris.withAppendedId(aUri, rowId);

            return rowUri;
        }

        throw new SQLException("Failed to insert row into " + aUri);
    }

    @Override
    public Cursor query(Uri aUri, String[] aProjection, String aSelection,
        String[] aSelectionArgs, String aSortOrder) 
    {
        SQLiteQueryBuilder builder = new SQLiteQueryBuilder();
		// Typically this should fetch a readable database but we're querying before
		// we actually add anything, so make it writable.
        SQLiteDatabase db = mDbHelper.getReadableDatabase();

        final int match = sUriMatcher.match(aUri);
        switch(match) {
			case FORUM_ID:
                aSelectionArgs = insertSelectionArg(aSelectionArgs, aUri.getLastPathSegment());        
                builder.appendWhere(AwfulForum.ID + "=?");
			case FORUM:
				builder.setTables(TABLE_FORUM);
				builder.setProjectionMap(sForumProjectionMap);
				break;
			case POST_ID:
                aSelectionArgs = insertSelectionArg(aSelectionArgs, aUri.getLastPathSegment());        
                builder.appendWhere(AwfulPost.ID + "=?");
			case POST:
				builder.setTables(TABLE_POSTS);
				builder.setProjectionMap(sPostProjectionMap);
				break;
			case THREAD_ID:
                aSelectionArgs = insertSelectionArg(aSelectionArgs, aUri.getLastPathSegment());        
                builder.appendWhere(AwfulThread.ID + "=?");
			case THREAD:
				builder.setTables(TABLE_THREADS);
				builder.setProjectionMap(sThreadProjectionMap);
				break;
			case UCP_THREAD_ID:
                aSelectionArgs = insertSelectionArg(aSelectionArgs, aUri.getLastPathSegment());        
                builder.appendWhere(AwfulThread.ID + "=?");
			case UCP_THREAD:
				//hopefully this join works
				builder.setTables(TABLE_UCP_THREADS+", "+TABLE_THREADS+" ON "+TABLE_UCP_THREADS+"."+AwfulThread.ID+"="+TABLE_THREADS+"."+AwfulThread.ID);
				builder.setProjectionMap(sUCPThreadProjectionMap);
				break;
        }

        Cursor result = builder.query(db, aProjection, aSelection, 
            aSelectionArgs, null, null, aSortOrder);
        result.setNotificationUri(getContext().getContentResolver(), aUri);

        return result;
    }

    /**
      * Inserts an argument at the beginning of the selection arg list.
      *
      * The {@link android.database.sqlite.SQLiteQueryBuilder}'s where clause is
      * prepended to the user's where clause (combined with 'AND') to generate
      * the final where close, so arguments associated with the QueryBuilder are
      * prepended before any user selection args to keep them in the right order.
      */
    private String[] insertSelectionArg(String[] selectionArgs, String arg) {
        if (selectionArgs == null) {
            return new String[] {arg};
        } else {
            int newLength = selectionArgs.length + 1;
            String[] newSelectionArgs = new String[newLength];
            newSelectionArgs[0] = arg;
            System.arraycopy(selectionArgs, 0, newSelectionArgs, 1, selectionArgs.length);
            return newSelectionArgs;
        }
    }

    private String appendWhere(String aWhere, String aAppend) {
        if (aWhere == null) {
            return aAppend;
        }

        return aWhere + " AND " + aAppend;
    }

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
		sForumProjectionMap = new HashMap<String, String>();
        sPostProjectionMap = new HashMap<String, String>();
        sThreadProjectionMap = new HashMap<String, String>();
        sUCPThreadProjectionMap = new HashMap<String, String>();

		sUriMatcher.addURI(Constants.AUTHORITY, "forum", FORUM);
		sUriMatcher.addURI(Constants.AUTHORITY, "forum/#", FORUM_ID);
		sUriMatcher.addURI(Constants.AUTHORITY, "thread", THREAD);
		sUriMatcher.addURI(Constants.AUTHORITY, "thread/#", THREAD_ID);
		sUriMatcher.addURI(Constants.AUTHORITY, "post", POST);
		sUriMatcher.addURI(Constants.AUTHORITY, "post/#", POST_ID);
		sUriMatcher.addURI(Constants.AUTHORITY, "ucpthread", UCP_THREAD);
		sUriMatcher.addURI(Constants.AUTHORITY, "ucpthread/#", UCP_THREAD_ID);

		sForumProjectionMap.put(AwfulForum.ID, AwfulForum.ID);
		sForumProjectionMap.put(AwfulForum.PARENT_ID, AwfulForum.PARENT_ID);
		sForumProjectionMap.put(AwfulForum.INDEX, AwfulForum.INDEX);
		sForumProjectionMap.put(AwfulForum.TITLE, AwfulForum.TITLE);
		sForumProjectionMap.put(AwfulForum.SUBTEXT, AwfulForum.SUBTEXT);
		sForumProjectionMap.put(AwfulForum.PAGE_COUNT, AwfulForum.PAGE_COUNT);

		sPostProjectionMap.put(AwfulPost.ID, AwfulPost.ID);
		sPostProjectionMap.put(AwfulPost.THREAD_ID, AwfulPost.THREAD_ID);
		sPostProjectionMap.put(AwfulPost.POST_INDEX, AwfulPost.POST_INDEX);
		sPostProjectionMap.put(AwfulPost.DATE, AwfulPost.DATE);
		sPostProjectionMap.put(AwfulPost.USER_ID, AwfulPost.USER_ID);
		sPostProjectionMap.put(AwfulPost.USERNAME, AwfulPost.USERNAME);
		sPostProjectionMap.put(AwfulPost.PREVIOUSLY_READ, AwfulPost.PREVIOUSLY_READ);
		sPostProjectionMap.put(AwfulPost.LAST_READ_URL, AwfulPost.LAST_READ_URL);
		sPostProjectionMap.put(AwfulPost.EDITABLE, AwfulPost.EDITABLE);
		sPostProjectionMap.put(AwfulPost.IS_OP, AwfulPost.IS_OP);
		sPostProjectionMap.put(AwfulPost.IS_ADMIN, AwfulPost.IS_ADMIN);
		sPostProjectionMap.put(AwfulPost.IS_MOD, AwfulPost.IS_MOD);
		sPostProjectionMap.put(AwfulPost.AVATAR, AwfulPost.AVATAR);
		sPostProjectionMap.put(AwfulPost.CONTENT, AwfulPost.CONTENT);
		sPostProjectionMap.put(AwfulPost.EDITED, AwfulPost.EDITED);
		
		sThreadProjectionMap.put(AwfulThread.ID, AwfulThread.ID);
		sThreadProjectionMap.put(AwfulThread.FORUM_ID, AwfulThread.FORUM_ID);
		sThreadProjectionMap.put(AwfulThread.INDEX, AwfulThread.INDEX);
		sThreadProjectionMap.put(AwfulThread.TITLE, AwfulThread.TITLE);
		sThreadProjectionMap.put(AwfulThread.POSTCOUNT, AwfulThread.POSTCOUNT);
		sThreadProjectionMap.put(AwfulThread.UNREADCOUNT, AwfulThread.UNREADCOUNT);
		sThreadProjectionMap.put(AwfulThread.AUTHOR, AwfulThread.AUTHOR);
		sThreadProjectionMap.put(AwfulThread.AUTHOR_ID, AwfulThread.AUTHOR_ID);
		sThreadProjectionMap.put(AwfulThread.LOCKED, AwfulThread.LOCKED);
		sThreadProjectionMap.put(AwfulThread.BOOKMARKED, AwfulThread.BOOKMARKED);
		sThreadProjectionMap.put(AwfulThread.STICKY, AwfulThread.STICKY);
		sThreadProjectionMap.put(AwfulThread.CATEGORY, AwfulThread.CATEGORY);
		sThreadProjectionMap.put(AwfulThread.LASTPOSTER, AwfulThread.LASTPOSTER);
		
		
		//hopefully this should let the join happen
		//but documentation on projection maps is fucking scarce.
		sUCPThreadProjectionMap.put(AwfulThread.ID, TABLE_THREADS+"."+AwfulThread.ID+" AS "+AwfulThread.ID);//threads._id AS _id
		sUCPThreadProjectionMap.put(AwfulThread.FORUM_ID, AwfulThread.FORUM_ID);
		sUCPThreadProjectionMap.put(AwfulThread.INDEX, TABLE_UCP_THREADS+"."+AwfulThread.INDEX+" AS "+AwfulThread.INDEX);
		sUCPThreadProjectionMap.put(AwfulThread.TITLE, AwfulThread.TITLE);
		sUCPThreadProjectionMap.put(AwfulThread.POSTCOUNT, AwfulThread.POSTCOUNT);
		sUCPThreadProjectionMap.put(AwfulThread.UNREADCOUNT, AwfulThread.UNREADCOUNT);
		sUCPThreadProjectionMap.put(AwfulThread.AUTHOR, AwfulThread.AUTHOR);
		sUCPThreadProjectionMap.put(AwfulThread.AUTHOR_ID, AwfulThread.AUTHOR_ID);
		sUCPThreadProjectionMap.put(AwfulThread.LOCKED, AwfulThread.LOCKED);
		sUCPThreadProjectionMap.put(AwfulThread.BOOKMARKED, AwfulThread.BOOKMARKED);
		sUCPThreadProjectionMap.put(AwfulThread.STICKY, AwfulThread.STICKY);
		sUCPThreadProjectionMap.put(AwfulThread.CATEGORY, AwfulThread.CATEGORY);
		sUCPThreadProjectionMap.put(AwfulThread.LASTPOSTER, AwfulThread.LASTPOSTER);
    }
}
