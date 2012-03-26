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

package com.ferg.awful;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.text.Html;
import android.text.format.DateFormat;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.webkit.*;
import android.webkit.WebSettings.RenderPriority;
import android.widget.*;
import android.support.v4.app.*;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.json.*;

import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.ferg.awful.constants.Constants;
import com.ferg.awful.preferences.AwfulPreferences;
import com.ferg.awful.preferences.ColorPickerPreference;
import com.ferg.awful.provider.AwfulProvider;
import com.ferg.awful.reply.Reply;
import com.ferg.awful.service.AwfulCursorAdapter;
import com.ferg.awful.service.AwfulSyncService;
import com.ferg.awful.thread.AwfulMessage;
import com.ferg.awful.thread.AwfulPagedItem;
import com.ferg.awful.thread.AwfulPost;
import com.ferg.awful.thread.AwfulThread;
import com.ferg.awful.widget.NumberPicker;
import com.ferg.awful.widget.SnapshotWebView;

/**
 * Uses intent extras:
 *  TYPE - STRING ID - DESCRIPTION
 *	int - Constants.THREAD_ID - id number for that thread
 *	int - Constants.THREAD_PAGE - page number to load
 *
 *  Can also handle an HTTP intent that refers to an SA showthread.php? url.
 */
public class ThreadDisplayFragment extends AwfulFragment implements AwfulUpdateCallback {
    private static final String TAG = "ThreadDisplayActivity";

    private PostLoaderManager mPostLoaderCallback;
    private ThreadDataCallback mThreadLoaderCallback;

    private ImageButton mNextPage;
    private ImageButton mPrevPage;
    private ImageButton mRefreshBar;
    private ImageView mSnapshotView;
    private TextView mPageCountText;
    private ProgressDialog mDialog;
    private ViewGroup mThreadWindow;

    private SnapshotWebView mThreadView;
    
    private boolean imagesLoadingState;
    private boolean threadLoadingState;

    private int mPage = 1;
    private int mThreadId = 0;
    private int mLastPage = 0;
    private int mParentForumId = 0;
    private int mReplyDraftSaved = 0;
    private String mDraftTimestamp = null;
    private boolean threadClosed = false;
    private boolean threadBookmarked = false;
    private boolean dataLoaded = false;
    
    private String mTitle = null;
    
	private String mPostJump = "";
	
	public static ThreadDisplayFragment newInstance(int id, int page) {
		ThreadDisplayFragment fragment = new ThreadDisplayFragment();

        fragment.setThreadId(id);
        fragment.setPage(page);

        return fragment;
	}

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message aMsg) {
            switch (aMsg.what) {
                case AwfulSyncService.MSG_SYNC_THREAD:
                    handleStatusUpdate(aMsg.arg1);
                    if(aMsg.arg1 != AwfulSyncService.Status.WORKING && getActivity() != null){
                    	refreshPosts();
                    }
                    break;
                case AwfulSyncService.MSG_SET_BOOKMARK:
                    handleStatusUpdate(aMsg.arg1);
                	refreshInfo();
                    break;
                case AwfulSyncService.MSG_MARK_LASTREAD:
                    handleStatusUpdate(aMsg.arg1);
                	refreshInfo();
                    if(aMsg.arg1 == AwfulSyncService.Status.OKAY && getActivity() != null){
                    	refreshPosts();
                    }
                    break;
                default:
                    super.handleMessage(aMsg);
            }
        }
    };

    private Messenger mMessenger = new Messenger(mHandler);
    private ThreadContentObserver mThreadObserver = new ThreadContentObserver(mHandler);

    
	
	private WebViewClient callback = new WebViewClient(){
		@Override
		public void onPageFinished(WebView view, String url) {
			if (imagesLoadingState) {
				imagesLoadingState = false;
				imageLoadingFinished();
			}
			if(!isResumed()){
				Log.e(TAG,"PageFinished after pausing. Forcing Webview.pauseTimers");
				mHandler.postDelayed(new Runnable(){
					@Override
					public void run() {
						if(mThreadView != null){
							mThreadView.pauseTimers();
							mThreadView.onPause();
						}
					}
				}, 500);
			}
		}

		public void onLoadResource(WebView view, String url) {
			if (!threadLoadingState && !imagesLoadingState && url != null && url.startsWith("http")) {
				imagesLoadingState = true;
				imageLoadingStarted();
			}
		}

		@Override
		public boolean shouldOverrideUrlLoading(WebView aView, String aUrl) {
			Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(aUrl));
			PackageManager pacman = aView.getContext().getPackageManager();
			List<ResolveInfo> res = pacman.queryIntentActivities(browserIntent,
					PackageManager.MATCH_DEFAULT_ONLY);
			if (res.size() > 0) {
				browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				aView.getContext().startActivity(browserIntent);
			} else {
				String[] split = aUrl.split(":");
				Toast.makeText(
						aView.getContext(),
						"No application found for protocol"
								+ (split.length > 0 ? ": " + split[0] : "."), Toast.LENGTH_LONG)
						.show();
			}
			return true;
		}
	};

    @Override
    public void onAttach(Activity aActivity) {
        super.onAttach(aActivity); Log.e(TAG, "onAttach");

        mPrefs = new AwfulPreferences(getActivity(), this);
    }

    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState); Log.e(TAG, "onCreate");
        setHasOptionsMenu(true);
        //setRetainInstance(true);
        
        String c2pThreadID = null;
        String c2pPostPerPage = null;
        String c2pPage = null;
        String c2pURLFragment = null;
        Intent data = getActivity().getIntent();
        // We may be getting thread info from a link or Chrome2Phone so handle that here
        if (data.getData() != null && data.getScheme().equals("http")) {
            c2pThreadID = data.getData().getQueryParameter("threadid");
            c2pPostPerPage = data.getData().getQueryParameter("perpage");
            c2pPage = data.getData().getQueryParameter("pagenumber");
            c2pURLFragment = data.getData().getEncodedFragment();
        }
        if(mThreadId < 1){
	        mThreadId = data.getIntExtra(Constants.THREAD_ID, mThreadId);
	        mPage = data.getIntExtra(Constants.THREAD_PAGE, mPage);
	        if (c2pThreadID != null) {
	        	mThreadId = Integer.parseInt(c2pThreadID);
	        }
	        if (c2pPage != null) {
	        	int page = Integer.parseInt(c2pPage);
	
	        	if (c2pPostPerPage != null && c2pPostPerPage.matches("\\d+")) {
	        		int ppp = Integer.parseInt(c2pPostPerPage);
	
	        		if (mPrefs.postPerPage != ppp) {
	        			page = (int) Math.ceil((double)(page*ppp) / mPrefs.postPerPage);
	        		}
	        	} else {
	        		if (mPrefs.postPerPage != Constants.ITEMS_PER_PAGE) {
	        			page = (int) Math.ceil((page*Constants.ITEMS_PER_PAGE)/(double)mPrefs.postPerPage);
	        		}
	        	}
	        	mPage = page;
	        	if (c2pURLFragment != null && c2pURLFragment.startsWith("post")) {
	        		setPostJump(c2pURLFragment.replaceAll("\\D", ""));
	        	}
	        }
        }
        if (savedInstanceState != null) {
        	Log.e(TAG, "onCreate savedState");
            mThreadId = savedInstanceState.getInt(Constants.THREAD_ID, mThreadId);
    		mPage = savedInstanceState.getInt(Constants.THREAD_PAGE, mPage);
        }
        
        mPostLoaderCallback = new PostLoaderManager();
        mThreadLoaderCallback = new ThreadDataCallback();

        getAwfulActivity().registerSyncService(mMessenger, getThreadId());
        if(getThreadId() > 0){
        	syncThread();
        }
    }
//--------------------------------
    @Override
    public View onCreateView(LayoutInflater aInflater, ViewGroup aContainer, Bundle aSavedState) {
        super.onCreateView(aInflater, aContainer, aSavedState); Log.e(TAG, "onCreateView");
        View result = aInflater.inflate(R.layout.thread_display, aContainer, false);

		mPageCountText = (TextView) result.findViewById(R.id.page_count);
		mNextPage = (ImageButton) result.findViewById(R.id.next_page);
		mPrevPage = (ImageButton) result.findViewById(R.id.prev_page);
        mRefreshBar  = (ImageButton) result.findViewById(R.id.refresh);
		mThreadView = (SnapshotWebView) result.findViewById(R.id.thread);
		mSnapshotView = (ImageView) result.findViewById(R.id.snapshot);
		mThreadWindow = (FrameLayout) result.findViewById(R.id.thread_window);
		initThreadViewProperties();
		mNextPage.setOnClickListener(onButtonClick);
		mPrevPage.setOnClickListener(onButtonClick);
		mRefreshBar.setOnClickListener(onButtonClick);
		updatePageBar();

		return result;
	}

	@Override
	public void onActivityCreated(Bundle aSavedState) {
		super.onActivityCreated(aSavedState); Log.e(TAG, "onActivityCreated");
        if(dataLoaded){
        	refreshPosts();
        }
	}

	private void initThreadViewProperties() {
		mThreadView.resumeTimers();
		mThreadView.setWebViewClient(callback);
		mThreadView.setBackgroundColor(mPrefs.postBackgroundColor);
		mThreadView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
		mThreadView.setSnapshotView(mSnapshotView);
		mThreadView.getSettings().setJavaScriptEnabled(true);
		mThreadView.getSettings().setRenderPriority(RenderPriority.HIGH);
        mThreadView.getSettings().setDefaultZoom(WebSettings.ZoomDensity.MEDIUM);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			mThreadView.getSettings().setEnableSmoothTransition(true);
		}

		mThreadView.setWebChromeClient(new WebChromeClient() {
			public void onConsoleMessage(String message, int lineNumber, String sourceID) {
				Log.d("Web Console", message + " -- From line " + lineNumber + " of " + sourceID);
			}
		});
	}
	
	public void updatePageBar(){
		mPageCountText.setText("Page " + getPage() + "/" + (getLastPage()>0?getLastPage():"?"));
		if(getActivity() != null){
			getAwfulActivity().invalidateOptionsMenu();
		}
		if (getPage() <= 1) {
			mPrevPage.setVisibility(View.INVISIBLE);
		} else {
			mPrevPage.setVisibility(View.VISIBLE);
		}

		if (getPage() == getLastPage()) {
            mNextPage.setVisibility(View.GONE);
            mRefreshBar.setVisibility(View.VISIBLE);
		} else {
            mNextPage.setVisibility(View.VISIBLE);
            mRefreshBar.setVisibility(View.GONE);
		}
	}

    @Override
    public void onStart() {
        super.onStart(); Log.e(TAG, "onStart");
        
    }
    

    @Override
    public void onResume() {
        super.onResume(); Log.e(TAG, "Resume");
        
        if (mThreadWindow.getChildCount() < 2) {
            mThreadView = new SnapshotWebView(getActivity());

            initThreadViewProperties();

            mThreadWindow.addView(mThreadView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
        }else{
        	if(mThreadView != null){
                mThreadView.onResume();
                mThreadView.resumeTimers();
        	}
        }
        getActivity().getContentResolver().registerContentObserver(AwfulThread.CONTENT_URI, true, mThreadObserver);
        refreshInfo();
    }
    
    
    @Override
    public void onPause() {
        super.onPause(); Log.e(TAG, "onPause");
        getActivity().getContentResolver().unregisterContentObserver(mThreadObserver);
        getLoaderManager().destroyLoader(Integer.MAX_VALUE-getThreadId());
        mThreadView.pauseTimers();
        mThreadView.stopLoading();
        mThreadView.onPause();
    }
        
    @Override
    public void onStop() {
        super.onStop(); Log.e(TAG, "onStop");
    }
    
    @Override
    public void onDestroyView(){
    	super.onDestroyView(); Log.e(TAG, "onDestroyView");
        try {
            mThreadWindow.removeView(mThreadView);
            mThreadView.destroy();
            mThreadView = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    @Override
    public void onDestroy() {
        super.onDestroy(); Log.e(TAG, "onDestroy");
        getLoaderManager().destroyLoader(getThreadId());
        getAwfulActivity().unregisterSyncService(mMessenger, getThreadId());
    }

    @Override
    public void onDetach() {
        super.onDetach(); Log.e(TAG, "onDetach");
    }
    
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) { 
    	Log.e(TAG, "onCreateOptionsMenu");
        if(menu.size() == 0){
            inflater.inflate(R.menu.post_menu, menu);
        }
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
    	Log.e(TAG, "onCreateOptionsMenu");
        if(menu == null){
            return;
        }

        MenuItem bk = menu.findItem(R.id.bookmark);
        if(bk != null){
        	bk.setTitle((threadBookmarked? getString(R.string.unbookmark):getString(R.string.bookmark)));
        }
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.next_page:
            	goToPage(getPage() + 1);
                break;
            case R.id.reply:
                displayPostReplyDialog();
                break;
            case R.id.usercp:
                displayUserCP();
                break;
            case R.id.go_to:
                displayPagePicker();
                break;
            case R.id.refresh:
                refresh();
                break;
            case R.id.settings:
                startActivity(new Intent().setClass(getActivity(), SettingsActivity.class));
                break;
            case R.id.bookmark:
            	toggleThreadBookmark();
                break;
    		case R.id.rate_thread:
    			rateThread();
    			break;
    		case R.id.copy_url:
    			copyThreadURL(null);
    			break;
    		//case R.id.find://TODO oops, broke this
    		//	this.mThreadView.showFindDialog(null, true);
    		//	break;
    		default:
    			return super.onOptionsItemSelected(item);
    		}

    		return true;
    	}
    
    private void launchParentForum(){
    	if(mParentForumId > 0){
    		startActivity(new Intent(getActivity(), ForumsIndexActivity.class).putExtra(Constants.FORUM_ID, mParentForumId));
    	}else{
    		getActivity().finish();
    	}
    	
    }

	private void copyThreadURL(String postId) {
		StringBuffer url = new StringBuffer();
		url.append(Constants.FUNCTION_THREAD);
		url.append("?");
		url.append(Constants.PARAM_THREAD_ID);
		url.append("=");
		url.append(getThreadId());
		url.append("&");
		url.append(Constants.PARAM_PAGE);
		url.append("=");
		url.append(getPage());
		url.append("&");
		url.append(Constants.PARAM_PER_PAGE);
		url.append("=");
		url.append(mPrefs.postPerPage);
		if (postId != null) {
			url.append("#");
			url.append("post");
			url.append(postId);
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			ClipboardManager clipboard = (ClipboardManager) this.getActivity().getSystemService(
					Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText(this.getText(R.string.copy_url).toString() + this.mPage, url.toString());
			clipboard.setPrimaryClip(clip);

			Toast successToast = Toast.makeText(this.getActivity().getApplicationContext(),
					getString(R.string.copy_url_success), Toast.LENGTH_SHORT);
			successToast.show();
		} else {
			AlertDialog.Builder alert = new AlertDialog.Builder(this.getActivity());

			alert.setTitle("URL");

			final EditText input = new EditText(this.getActivity());
			input.setText(url.toString());
			alert.setView(input);

			alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
					dialog.dismiss();
				}
			});

			alert.show();
		}
	}

    	private void rateThread() {

    		final CharSequence[] items = { "1", "2", "3", "4", "5" };

    		AlertDialog.Builder builder = new AlertDialog.Builder(this.getActivity());
    		builder.setTitle("Rate this thread");
    		builder.setItems(items, new DialogInterface.OnClickListener() {
    			public void onClick(DialogInterface dialog, int item) {
    				if (getActivity() != null) {
    					getAwfulActivity().sendMessage(AwfulSyncService.MSG_VOTE,
    							getThreadId(), item);
    				}
    			}
    		});
    		AlertDialog alert = builder.create();
    		alert.show();
    	}

    
    @Override
    public void onSaveInstanceState(Bundle outState){
    	super.onSaveInstanceState(outState);
    	Log.v(TAG,"onSaveInstanceState");
        outState.putInt(Constants.THREAD_PAGE, getPage());
    	outState.putInt(Constants.THREAD_ID, getThreadId());
    }
    
    private void syncThread() {
        if(getActivity() != null){
        	dataLoaded = false;
        	getAwfulActivity().sendMessage(AwfulSyncService.MSG_SYNC_THREAD,getThreadId(),getPage());
        }
    }
    
    private void markLastRead(int index) {
        if(getActivity() != null){
        	getAwfulActivity().sendMessage(AwfulSyncService.MSG_MARK_LASTREAD,getThreadId(),index);
        }
    }

    private void toggleThreadBookmark() {
        if(getActivity() != null){
        	getAwfulActivity().sendMessage(AwfulSyncService.MSG_SET_BOOKMARK,getThreadId(),(threadBookmarked?0:1));
        }
    }

    private void displayUserCP() {
    	//TODO update to splitview
        startActivity(new Intent().setClass(getActivity(), ForumsIndexActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(Constants.FORUM_ID, Constants.USERCP_ID));
    }

    private void displayPagePicker() {
        final NumberPicker jumpToText = new NumberPicker(getActivity());

         
        jumpToText.setRange(1, getLastPage());
        jumpToText.setCurrent(getPage());
        new AlertDialog.Builder(getActivity())
            .setTitle("Jump to Page")
            .setView(jumpToText)
            .setPositiveButton("OK",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface aDialog, int aWhich) {
                        try {
                            int pageInt = jumpToText.getCurrent();
                            if (pageInt > 0 && pageInt <= getLastPage()) {
                                goToPage(pageInt);
                            }
                        } catch (NumberFormatException e) {
                            Toast.makeText(getActivity(),
                                R.string.invalid_page, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.d(TAG, e.toString());
                        }
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
        
    }

    private boolean onPostActionItemSelected(int aItem, String aPostId, int aLastReadIndex, String aUsername) {
        switch (aItem) {
            case ClickInterface.EDIT:
            	if (aUsername != null){
            		//TODO update this I guess
                        MessageFragment.newInstance(aUsername, 0).show(getFragmentManager(), "new_private_message_dialog");
            	}else{
                    Bundle args = new Bundle();

                    args.putInt(Constants.THREAD_ID, mThreadId);
                    args.putInt(Constants.EDITING, AwfulMessage.TYPE_EDIT);
                    args.putInt(Constants.POST_ID, Integer.parseInt(aPostId));


                    if(mReplyDraftSaved >0){
                    	displayDraftAlert(mReplyDraftSaved, mDraftTimestamp, args);
                    }else{
                        displayPostReplyDialog(args);
                    }
            	}
                return true;
            case ClickInterface.QUOTE:
                Bundle args = new Bundle();
                args.putInt(Constants.THREAD_ID, mThreadId);
                args.putInt(Constants.POST_ID, Integer.parseInt(aPostId));
                args.putInt(Constants.EDITING, AwfulMessage.TYPE_QUOTE);

                if(mReplyDraftSaved >0){
                	displayDraftAlert(mReplyDraftSaved, mDraftTimestamp, args);
                }else{
                    displayPostReplyDialog(args);
                }
                return true;
            case ClickInterface.LAST_READ:
            	markLastRead(aLastReadIndex);
                return true;
            case ClickInterface.COPY_URL:
            	copyThreadURL(aPostId);
            	break;
        }

        return false;
    }
    
    @Override
    public void onActivityResult(int aRequestCode, int aResultCode, Intent aData) {
        // If we're here because of a post result, refresh the thread
        switch (aRequestCode) {
            case PostReplyFragment.RESULT_POSTED:
                getAwfulActivity().registerSyncService(mMessenger, getThreadId());
            	if(getPage() < getLastPage()){
            		goToPage(getLastPage());
            	}else{
            		refresh();
            	}
                break;
        }
    }

    public void refresh() {
		mThreadView.loadData(getBlankPage(), "text/html", "utf-8");
        syncThread();
    }

    private View.OnClickListener onButtonClick = new View.OnClickListener() {
        public void onClick(View aView) {
            switch (aView.getId()) {
                case R.id.next_page:
                case R.id.next_page_top:
                	goToPage(getPage() + 1);
                    break;
                case R.id.prev_page:
                	goToPage(getPage() - 1);
                    break;
                case R.id.reply:
                    displayPostReplyDialog();
                    break;
                case R.id.refresh_top:
                case R.id.refresh:
                	if(imagesLoadingState && mThreadView != null){
                		mThreadView.stopLoading();
                		imagesLoadingState = false;
                		imageLoadingFinished();
                	}else{
                		refresh();
                	}
                    break;
            }
        }
    };

    public void displayPostReplyDialog() {
        Bundle args = new Bundle();
        args.putInt(Constants.THREAD_ID, mThreadId);
        args.putInt(Constants.EDITING, AwfulMessage.TYPE_NEW_REPLY);

        if(mReplyDraftSaved >0){
        	displayDraftAlert(mReplyDraftSaved, mDraftTimestamp, args);
        }else{
            displayPostReplyDialog(args);
        }
    }

    public void displayPostReplyDialog(Bundle aArgs) {
    	startActivityForResult(new Intent(getActivity(), PostReplyActivity.class).putExtras(aArgs), PostReplyFragment.RESULT_POSTED);
    	/*
            PostReplyFragment fragment = PostReplyFragment.newInstance(aArgs);
            fragment.setTargetFragment(this, PostReplyFragment.RESULT_POSTED);
            fragment.show(getActivity().getSupportFragmentManager(), "post_reply_dialog");
    */}
    
    private void displayDraftAlert(int replyType, String timeStamp, final Bundle aArgs) {
    	TextView draftAlertMsg = new TextView(getActivity());
    	if(timeStamp != null){
    	    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    	    sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
    	    try {
				Date d = sdf.parse(timeStamp);
				java.text.DateFormat df = DateFormat.getDateFormat(getActivity());
				df.setTimeZone(TimeZone.getDefault());
				timeStamp = df.format(d);
			} catch (ParseException e) {
				e.printStackTrace();
			}
    	}
    	switch(replyType){
    	case AwfulMessage.TYPE_EDIT:
        	draftAlertMsg.setText("Unsent Edit Found"+(timeStamp != null ? " from "+timeStamp : ""));
    		break;
    	case AwfulMessage.TYPE_QUOTE:
        	draftAlertMsg.setText("Unsent Quote Found"+(timeStamp != null ? " from "+timeStamp : ""));
    		break;
    	case AwfulMessage.TYPE_NEW_REPLY:
        	draftAlertMsg.setText("Unsent Reply Found"+(timeStamp != null ? " from "+timeStamp : ""));
    		break;
    	}
        new AlertDialog.Builder(getActivity())
            .setTitle("Draft Found")
            .setView(draftAlertMsg)
            .setPositiveButton(R.string.draft_alert_keep,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface aDialog, int aWhich) {
                        displayPostReplyDialog(aArgs);
                    }
                })
            .setNegativeButton(R.string.draft_alert_discard, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface aDialog, int aWhich) {
                    ContentResolver cr = getActivity().getContentResolver();
                    cr.delete(AwfulMessage.CONTENT_URI_REPLY, AwfulMessage.ID+"=?", AwfulProvider.int2StrArray(mThreadId));
                    displayPostReplyDialog(aArgs);
                }
            })
            .show();
        
    }

    private void handleStatusUpdate(int aStatus) {
        switch (aStatus) {
            case AwfulSyncService.Status.WORKING:
                loadingStarted();
                break;
            case AwfulSyncService.Status.OKAY:
                loadingSucceeded();
                break;
            case AwfulSyncService.Status.ERROR:
                loadingFailed();
                break;
        };
    }

    @Override
    public void loadingFailed() {
        if(getActivity() != null){
        	getAwfulActivity().setSupportProgressBarIndeterminateVisibility(false);
        	Toast.makeText(getActivity(), "Loading Failed!", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void loadingStarted() {
    	threadLoadingState = true;
    	if(getActivity() != null){
    		getAwfulActivity().setSupportProgressBarIndeterminateVisibility(true);
    	}
    }

    @Override
    public void loadingSucceeded() {
    	threadLoadingState = false;
    	if(getActivity() != null){
    		getAwfulActivity().setSupportProgressBarIndeterminateVisibility(false);
    	}
    }
    
    public void imageLoadingStarted() {
    	threadLoadingState = false;
    	if(getActivity() != null){
    		getAwfulActivity().setSupportProgressBarIndeterminateVisibility(true);
        }
    }
    
    public void imageLoadingFinished() {
    	if(getActivity() != null){
    		getAwfulActivity().setSupportProgressBarIndeterminateVisibility(false);
    	}
    }

    private void populateThreadView(ArrayList<AwfulPost> aPosts) {
		updatePageBar();

        try {
            mThreadView.addJavascriptInterface(new ClickInterface(), "listener");
            mThreadView.addJavascriptInterface(getSerializedPreferences(new AwfulPreferences(getActivity())), "preferences");

            mThreadView.loadDataWithBaseURL("http://forums.somethingawful.com", 
            		AwfulThread.getHtml(aPosts, new AwfulPreferences(getActivity()), largeScreen(), mPage == mLastPage), "text/html", "utf-8", null);//TODO fix
        } catch (Exception e) {
        	e.printStackTrace();
            // If we've already left the activity the webview may still be working to populate,
            // just log it
        }
        Log.i(TAG,"Finished populateThreadView, posts:"+aPosts.size());
    }

    private String getSerializedPreferences(final AwfulPreferences aAppPrefs) {
        JSONObject result = new JSONObject();

        try {
            result.put("username", aAppPrefs.username);
            result.put("userQuote", "#a2cd5a");
            result.put("usernameHighlight", "#9933ff");
            result.put("youtubeHighlight", "#ff00ff");
            result.put("showSpoilers", aAppPrefs.showAllSpoilers);
            result.put("postcolor", ColorPickerPreference.convertToARGB(aAppPrefs.postFontColor));
            result.put("backgroundcolor", ColorPickerPreference.convertToARGB(aAppPrefs.postBackgroundColor));
            result.put("linkQuoteColor", ColorPickerPreference.convertToARGB(aAppPrefs.postLinkQuoteColor));
            result.put("highlightUserQuote", Boolean.toString(aAppPrefs.highlightUserQuote));
            result.put("highlightUsername", Boolean.toString(aAppPrefs.highlightUsername));
            result.put("postjumpid", mPostJump);
        } catch (JSONException e) {
        }

        return result.toString();
    }

    private class ClickInterface {
        public static final int QUOTE     = 0;
        public static final int LAST_READ = 1;
        public static final int EDIT      = 2;
		public static final int COPY_URL = 3;

        final CharSequence[] mEditablePostItems = {
            "Quote", 
            "Mark last read",
            "Edit Post",
            "Copy Post URL"
        };
        final CharSequence[] mPostItems = {
            "Quote", 
            "Mark last read",
            "Send Private Message",
            "Copy Post URL"
        };

        // Post ID is the item tapped
        public void onPostClick(final String aPostId, final String aLastReadUrl, final String aUsername) {
            new AlertDialog.Builder(getActivity())
                .setTitle("Select an Action")
                .setItems(mPostItems, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface aDialog, int aItem) {
                        onPostActionItemSelected(aItem, aPostId, Integer.parseInt(aLastReadUrl), aUsername);
                    }
                })
                .show();
        }

        // Post ID is the item tapped
        public void onEditablePostClick(final String aPostId, final String aLastReadUrl) {
            new AlertDialog.Builder(getActivity())
                .setTitle("Select an Action")
                .setItems(mEditablePostItems, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface aDialog, int aItem) {
                        onPostActionItemSelected(aItem, aPostId, Integer.parseInt(aLastReadUrl), null);
                    }
                })
                .show();
        }

        public void onPreviousPageClick() {
            getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    goToPage(getPage() - 1);
                }
            });
        }

        public void onNextPageClick() {
            getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    goToPage(getPage() + 1);
                }
            });
        }

        public void onRefreshPageClick() {
            getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    refresh();
                }
            });
        }
    }

	@Override
	public void onPreferenceChange(AwfulPreferences mPrefs) {
		refreshPosts();
	}

	public void setPostJump(String postID) {
		mPostJump = postID;
	}
	
	public void goToPage(int aPage){
		if(aPage > 0 && aPage <= getLastPage()){
			setPage(aPage);
			updatePageBar();
			mPostJump = "";
            mThreadView.loadData(getBlankPage(), "text/html", "utf-8");
	        syncThread();
		}
	}
	
	private String getBlankPage(){
		return "<html><head></head><body style='{background-color:#"+ColorPickerPreference.convertToARGB(mPrefs.postBackgroundColor)+";'></body></html>";
	}
	
	public int getPage() {
        return mPage;
	}
	public void setPage(int aPage){
		mPage = aPage;
	}
	public void setThreadId(int aThreadId){
		mThreadId = aThreadId;
	}
	public int getLastPage() {
        return mLastPage;
	}

	public int getThreadId() {
        return mThreadId;
	}

    private class PostLoaderManager implements LoaderManager.LoaderCallbacks<Cursor> {
        private final static String sortOrder = AwfulPost.POST_INDEX + " ASC";
        private final static String selection = AwfulPost.THREAD_ID + "=? AND " + AwfulPost.POST_INDEX + ">=? AND " + AwfulPost.POST_INDEX + "<?";
        public Loader<Cursor> onCreateLoader(int aId, Bundle aArgs) {
            int index = AwfulPagedItem.pageToIndex(getPage(), mPrefs.postPerPage, 0);
            Log.v(TAG,"Displaying thread: "+getThreadId()+" index: "+index+" page: "+getPage()+" perpage: "+mPrefs.postPerPage);
            return new CursorLoader(getActivity(),
            						AwfulPost.CONTENT_URI,
            						AwfulProvider.PostProjection,
            						selection,
            						AwfulProvider.int2StrArray(getThreadId(), index, index+mPrefs.postPerPage),
            						sortOrder);
        }

        public void onLoadFinished(Loader<Cursor> aLoader, Cursor aData) {
        	Log.i(TAG,"Load finished, page:"+getPage()+", populating: "+aData.getCount());
            populateThreadView(AwfulPost.fromCursor(getActivity(), aData));
            dataLoaded = true;
        }

        @Override
        public void onLoaderReset(Loader<Cursor> aLoader) {
        }
    }
    
    private class ThreadDataCallback implements LoaderManager.LoaderCallbacks<Cursor> {

        public Loader<Cursor> onCreateLoader(int aId, Bundle aArgs) {
            return new CursorLoader(getActivity(), ContentUris.withAppendedId(AwfulThread.CONTENT_URI, getThreadId()), 
            		AwfulProvider.ThreadProjection, null, null, null);
        }

        public void onLoadFinished(Loader<Cursor> aLoader, Cursor aData) {
        	Log.v(TAG,"Thread title finished, populating.");
        	if(aData.getCount() >0 && aData.moveToFirst()){
        		mLastPage = AwfulPagedItem.indexToPage(aData.getInt(aData.getColumnIndex(AwfulThread.POSTCOUNT)),mPrefs.postPerPage);
        		threadClosed = aData.getInt(aData.getColumnIndex(AwfulThread.LOCKED))>0;
        		threadBookmarked = aData.getInt(aData.getColumnIndex(AwfulThread.BOOKMARKED))>0;
        		mParentForumId = aData.getInt(aData.getColumnIndex(AwfulThread.FORUM_ID));
        		setTitle(aData.getString(aData.getColumnIndex(AwfulThread.TITLE)));
        		updatePageBar();
        		mReplyDraftSaved = aData.getInt(aData.getColumnIndex(AwfulMessage.TYPE));
        		if(mReplyDraftSaved > 0){
            		mDraftTimestamp = aData.getString(aData.getColumnIndex(AwfulProvider.UPDATED_TIMESTAMP));
            		//TODO add tablet notification
        			Log.i(TAG, "DRAFT SAVED: "+mReplyDraftSaved+" at "+mDraftTimestamp);
        		}
        	}
        }
        
        @Override
        public void onLoaderReset(Loader<Cursor> aLoader) {
        }
    }
    private class ThreadContentObserver extends ContentObserver {
        public ThreadContentObserver(Handler aHandler) {
            super(aHandler);
        }
        @Override
        public void onChange (boolean selfChange){
        	Log.e(TAG,"Thread Data update.");
        	refreshInfo();
        }
    }
    
    private static final AlphaAnimation mFlashingAnimation = new AlphaAnimation(1f, 0f);
	private static final RotateAnimation mLoadingAnimation = 
			new RotateAnimation(
					0f, 360f,
					Animation.RELATIVE_TO_SELF, 0.5f,
					Animation.RELATIVE_TO_SELF, 0.5f);
	static {
		mFlashingAnimation.setInterpolator(new LinearInterpolator());
		mFlashingAnimation.setRepeatCount(Animation.INFINITE);
		mFlashingAnimation.setDuration(500);
		mLoadingAnimation.setInterpolator(new LinearInterpolator());
		mLoadingAnimation.setRepeatCount(Animation.INFINITE);
		mLoadingAnimation.setDuration(700);
	}
	public void refreshInfo() {
		if(getActivity() != null){
			getLoaderManager().restartLoader(Integer.MAX_VALUE-getThreadId(), null, mThreadLoaderCallback);
		}
	}
	
	public void refreshPosts(){
		if(getActivity() != null){
			getLoaderManager().restartLoader(getThreadId(), null, mPostLoaderCallback);
		}
	}
	
	public boolean largeScreen(){
		return (getActivity().getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_XLARGE) > 0;
	}
	
	public void setTitle(String title){
		mTitle = title;
		if(getActivity() != null && mTitle != null){
			getAwfulActivity().setActionbarTitle(mTitle, this);
		}
	}
	
	public String getTitle(){
		return mTitle;
	}

	public int getParentForumId() {
		return mParentForumId;
	}

	public void openThread(int id, int page) {
    	if(getActivity() != null){
	        getAwfulActivity().unregisterSyncService(mMessenger, getThreadId());
	        getLoaderManager().destroyLoader(Integer.MAX_VALUE-getThreadId());
	        getLoaderManager().destroyLoader(getThreadId());
    	}
    	setThreadId(id);//if the fragment isn't attached yet, just set the values and let the lifecycle handle it
    	setPage(page);
    	dataLoaded = false;
    	mLastPage = 1;
		mPostJump = "";
		updatePageBar();
    	if(getActivity() != null){
	        getAwfulActivity().registerSyncService(mMessenger, getThreadId());
            mThreadView.loadData(getBlankPage(), "text/html", "utf-8");
			refreshInfo();
			syncThread();
    	}
	}
}
