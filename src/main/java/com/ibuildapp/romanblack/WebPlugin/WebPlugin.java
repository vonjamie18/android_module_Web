/****************************************************************************
 * *
 * Copyright (C) 2014-2015 iBuildApp, Inc. ( http://ibuildapp.com )         *
 * *
 * This file is part of iBuildApp.                                          *
 * *
 * This Source Code Form is subject to the terms of the iBuildApp License.  *
 * You can obtain one at http://ibuildapp.com/license/                      *
 * *
 ****************************************************************************/
package com.ibuildapp.romanblack.WebPlugin;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.MailTo;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.MimeTypeMap;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.appbuilder.sdk.android.AppBuilderModuleMain;
import com.appbuilder.sdk.android.StartUpActivity;
import com.appbuilder.sdk.android.Statics;
import com.appbuilder.sdk.android.Widget;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;


/**
 * Main module class. Module entry point.
 * Represents HTML, Web, Google Calendar and Facebook widgets.
 */
@StartUpActivity(moduleName = "Web")
public class WebPlugin extends AppBuilderModuleMain {

    final private int SHOW_HTML = 0;
    final private int STOP_LOADING = 1;
    final private int INITIALIZATION_FAILED = 3;
    final private int NEED_INTERNET_CONNECTION = 4;
    final private int SHOW_PROGRESS = 5;
    final private int HIDE_PROGRESS = 6;
    final private int LOADING_ABORTED = 7;
    final private int DOWNLOAD_REQUEST_CODE = 1000;
    final private int DOWNLOAD_REQUEST_CODE_WITHOUT_START = 1001;
    final private int FILECHOOSER_RESULTCODE = 10002;
    LinearLayout panel = null;
    boolean hideProgress;
    private String appName;
    private boolean needRefresh = false;
    private boolean alreadyLoaded = false;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ObservableWebView webView = null;
    private Widget widget = null;
    private ProgressDialog progressDialog = null;
    private states state = states.EMPTY;
    private boolean isOnline = false;
    private boolean isMedia = false;
    private String url = "";
    private String html = "";
    private String currentUrl = "";
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case INITIALIZATION_FAILED: {
                    Toast.makeText(WebPlugin.this, R.string.romanblack_html_cannot_init, Toast.LENGTH_LONG).show();
                    closeActivity();
                }
                break;
                case NEED_INTERNET_CONNECTION: {
                    Toast.makeText(WebPlugin.this, R.string.romanblack_html_alert_no_internet, Toast.LENGTH_LONG).show();
                    handler.sendEmptyMessage(HIDE_PROGRESS);
                    closeActivity();
                }
                break;
                case SHOW_HTML: {
                    showHtml();
                }
                break;
                case STOP_LOADING: {
                    handler.sendEmptyMessage(HIDE_PROGRESS);
                    Toast.makeText(WebPlugin.this, R.string.romanblack_html_alert_no_internet, Toast.LENGTH_LONG).show();
                    webView.stopLoading();
                }
                break;
                case SHOW_PROGRESS: {
                    if (!isFinishing())
                        showProgress();
                }
                break;
                case HIDE_PROGRESS: {
                    if (!isFinishing())
                        hideProgress();
                }
                break;
                case LOADING_ABORTED: {
//                    closeActivity();
                }
                break;
            }
        }
    };
    private ValueCallback<Uri> mUploadMessage;
    private ValueCallback<Uri[]> mUploadMessageV21;
    private boolean isV21 = false;
    @Override
    public void create() {
        try {

            setContentView(R.layout.romanblack_html_main);
            root = (FrameLayout) findViewById(R.id.romanblack_root_layout);
            webView = new ObservableWebView(this);
            webView.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT));
            root.addView(webView);


            webView.setHorizontalScrollBarEnabled(false);
            setTitle("HTML");

            Intent currentIntent = getIntent();
            Bundle store = currentIntent.getExtras();
            widget = (Widget) store.getSerializable("Widget");
            if (widget == null) {
                handler.sendEmptyMessageDelayed(INITIALIZATION_FAILED, 100);
                return;
            }
            appName = widget.getAppName();

            if (widget.getPluginXmlData().length() == 0) {
                if (currentIntent.getStringExtra("WidgetFile").length() == 0) {
                    handler.sendEmptyMessageDelayed(INITIALIZATION_FAILED, 100);
                    return;
                }
            }

            if (widget.getTitle() != null && widget.getTitle().length() > 0) {
                setTopBarTitle(widget.getTitle());
            } else {
                setTopBarTitle(getResources().getString(R.string.romanblack_html_web));
            }


            currentUrl = (String) getSession();
            if (currentUrl == null) {
                currentUrl = "";
            }


            ConnectivityManager cm = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnectedOrConnecting()) {
                isOnline = true;
            }

            // topbar initialization
            setTopBarLeftButtonText(getString(R.string.common_home_upper), true, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onBackPressed();
                }
            });

            if (isOnline) {
                webView.getSettings().setJavaScriptEnabled(true);
            }

            webView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
            webView.getSettings().setGeolocationEnabled(true);
            webView.getSettings().setAllowFileAccess(true);
            webView.getSettings().setAppCacheEnabled(true);
            webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
            webView.getSettings().setBuiltInZoomControls(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setUseWideViewPort(false);
            webView.getSettings().setSavePassword(false);
            webView.clearHistory();
            webView.invalidate();

            if (Build.VERSION.SDK_INT >= 19){}
               webView.getSettings().setUserAgentString("Mozilla/5.0 (Linux; U; Android 2.2.1; en-us; Nexus One Build/FRG83) AppleWebKit/533.1 (KHTML, like Gecko) Version/4.0 Mobile Safari/533.1");

            webView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    v.invalidate();

                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN: {
                        }
                        break;
                        case MotionEvent.ACTION_UP: {
                            if (!v.hasFocus()) {
                                v.requestFocus();
                            }
                        }
                        break;

                        case MotionEvent.ACTION_MOVE: {
                        }
                        break;

                    }
                    return false;
                }
            });

            webView.setBackgroundColor(Color.WHITE);
            try {
                if (widget.getBackgroundColor() != Color.TRANSPARENT) {
                    webView.setBackgroundColor(widget.getBackgroundColor());
                }
            } catch (IllegalArgumentException e) {
            }

            webView.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url,
                                            String userAgent,
                                            String contentDisposition,
                                            String mimetype,
                                            long contentLength) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(url));
                    startActivity(intent);
                }
            });

            webView.setWebChromeClient(new WebChromeClient() {

                FrameLayout.LayoutParams LayoutParameters = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);


                @Override
                public void onGeolocationPermissionsShowPrompt(final String origin, final GeolocationPermissions.Callback callback) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(WebPlugin.this);
                    builder.setTitle(R.string.location_dialog_title);
                    builder.setMessage(R.string.location_dialog_description);
                    builder.setCancelable(true);

                    builder.setPositiveButton(R.string.location_dialog_allow, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            callback.invoke(origin, true, false);
                        }
                    });

                    builder.setNegativeButton(R.string.location_dialog_not_allow, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            callback.invoke(origin, false, false);
                        }
                    });

                    AlertDialog alert = builder.create();
                    alert.show();
                }

                @Override
                public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                    if (customView != null) {
                        customViewCallback.onCustomViewHidden();
                        return;
                    }

                    view.setBackgroundColor(Color.BLACK);
                    view.setLayoutParams(LayoutParameters);
                    root.addView(view);
                    customView = view;
                    customViewCallback = callback;
                    webView.setVisibility(View.GONE);
                }

                @Override
                public void onHideCustomView() {
                    if (customView == null) {
                        return;
                    } else {
                        closeFullScreenVideo();
                    }
                }

                public void openFileChooser(ValueCallback<Uri> uploadMsg) {

                    mUploadMessage = uploadMsg;
                    Intent i = new Intent( Intent.ACTION_GET_CONTENT );//Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    isMedia = true;
                    startActivityForResult(Intent.createChooser(i, "File Chooser"), FILECHOOSER_RESULTCODE);

                }

                // For Android 3.0+
                public void openFileChooser(ValueCallback uploadMsg, String acceptType) {
                    mUploadMessage = uploadMsg;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(
                            Intent.createChooser(i, "File Browser"),
                            FILECHOOSER_RESULTCODE);
                }

                //For Android 4.1
                public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                    mUploadMessage = uploadMsg;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    isMedia = true;
                    i.setType("image/*");
                    startActivityForResult(Intent.createChooser(i, "File Chooser"), FILECHOOSER_RESULTCODE);

                }

                @Override
                public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                    isV21 = true;
                    mUploadMessageV21 = filePathCallback;
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    startActivityForResult(Intent.createChooser(i, "File Chooser"), FILECHOOSER_RESULTCODE);
                    return true;
                }

                @Override
                public void onReceivedTouchIconUrl(WebView view, String url, boolean precomposed) {
                    super.onReceivedTouchIconUrl(view, url, precomposed);
                }
            });

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);

                    if (state == states.EMPTY) {
                        currentUrl = url;
                        setSession(currentUrl);
                        state = states.LOAD_START;
                        handler.sendEmptyMessage(SHOW_PROGRESS);
                    }
                }

                @Override
                public void onLoadResource(WebView view, String url) {

                    if (!alreadyLoaded && (url.startsWith("http://www.youtube.com/get_video_info?") || url.startsWith("https://www.youtube.com/get_video_info?")) && Build.VERSION.SDK_INT < 11) {
                        try {
                            String path = url.contains("https://www.youtube.com/get_video_info?") ?
                                    url.replace("https://www.youtube.com/get_video_info?", "") :
                                    url.replace("http://www.youtube.com/get_video_info?", "");

                            String[] parqamValuePairs = path.split("&");

                            String videoId = null;

                            for (String pair : parqamValuePairs) {
                                if (pair.startsWith("video_id")) {
                                    videoId = pair.split("=")[1];
                                    break;
                                }
                            }

                            if (videoId != null) {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.youtube.com"))
                                        .setData(Uri.parse("http://www.youtube.com/watch?v=" + videoId)));
                                needRefresh = true;
                                alreadyLoaded = !alreadyLoaded;

                                return;
                            }
                        } catch (Exception ex) {
                        }
                    } else {
                        super.onLoadResource(view, url);
                    }
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (hideProgress) {
                        if (TextUtils.isEmpty(WebPlugin.this.url)) {
                            state = states.LOAD_COMPLETE;
                            handler.sendEmptyMessage(HIDE_PROGRESS);
                            super.onPageFinished(view, url);
                        } else {
                            view.loadUrl("javascript:(function(){" +
                                    "l=document.getElementById('link');" +
                                    "e=document.createEvent('HTMLEvents');" +
                                    "e.initEvent('click',true,true);" +
                                    "l.dispatchEvent(e);" +
                                    "})()");
                            hideProgress = false;
                        }
                    } else {
                        state = states.LOAD_COMPLETE;
                        handler.sendEmptyMessage(HIDE_PROGRESS);
                        super.onPageFinished(view, url);
                    }
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    if (errorCode == WebViewClient.ERROR_BAD_URL) {
                        startActivityForResult(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(url)), DOWNLOAD_REQUEST_CODE);
                    }
                }

                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.cancel();
                }

                @Override
                public void onFormResubmission(WebView view, Message dontResend, Message resend) {
                    super.onFormResubmission(view, dontResend, resend);
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    try {

                        if (url.contains("youtube.com/watch")) {
                            if (Build.VERSION.SDK_INT < 11) {
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW,
                                            Uri.parse("http://www.youtube.com")).setData(Uri.parse(url)));
                                    return true;
                                } catch (Exception ex) {
                                    return false;
                                }
                            } else {
                                return false;
                            }
                        } else if (url.contains("paypal.com")) {
                            if (url.contains("&bn=ibuildapp_SP")) {
                                return false;
                            } else {
                                url = url + "&bn=ibuildapp_SP";

                                webView.loadUrl(url);

                                return true;
                            }
                        } else if (url.contains("sms:")) {
                            try {
                                Intent smsIntent = new Intent(Intent.ACTION_VIEW);
                                smsIntent.setData(Uri.parse(url));
                                startActivity(smsIntent);
                                return true;
                            } catch (Exception ex) {
                                Log.e("", ex.getMessage());
                                return false;
                            }
                        } else if (url.contains("tel:")) {
                            Intent callIntent = new Intent(Intent.ACTION_CALL);
                            callIntent.setData(Uri.parse(url));
                            startActivity(callIntent);
                            return true;
                        } else if (url.contains("mailto:")) {
                            MailTo mailTo = MailTo.parse(url);

                            Intent emailIntent =
                                    new Intent(android.content.Intent.ACTION_SEND);
                            emailIntent.setType("plain/text");
                            emailIntent.putExtra(android.content.Intent.EXTRA_EMAIL, new String[]{mailTo.getTo()});

                            emailIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, mailTo.getSubject());
                            emailIntent.putExtra(android.content.Intent.EXTRA_TEXT, mailTo.getBody());
                            WebPlugin.this.startActivity(Intent.createChooser(emailIntent, getString(R.string.romanblack_html_send_email)));
                            return true;
                        } else if (url.contains("rtsp:")) {
                            Uri address = Uri.parse(url);
                            Intent intent = new Intent(Intent.ACTION_VIEW, address);

                            final PackageManager pm = getPackageManager();
                            final List<ResolveInfo> matches = pm.queryIntentActivities(intent, 0);
                            if (matches.size() > 0) {
                                startActivity(intent);
                            } else {
                                Toast.makeText(WebPlugin.this, getString(R.string.romanblack_html_no_video_player), Toast.LENGTH_SHORT).show();
                            }

                            return true;
                        } else if (url.startsWith("intent:") ||
                                url.startsWith("market:") ||
                                url.startsWith("col-g2m-2:")) {
                            Intent it = new Intent();
                            it.setData(Uri.parse(url));
                            startActivity(it);

                            return true;
                        } else if (url.contains("//play.google.com/")) {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                            return true;
                        } else {
                            if (url.contains("ibuildapp.com-1915109")){
                                String param = Uri.parse(url).getQueryParameter("widget");
                                finish();
                                if (param!=null && param.equals("1001"))
                                    com.appbuilder.sdk.android.Statics.launchMain();
                                else if (param!=null && !"".equals(param)){
                                    View.OnClickListener widget = Statics.linkWidgets.get(Integer.valueOf(param));
                                    if (widget!=null)
                                        widget.onClick(view);
                                }
                                return false;
                            }

                            currentUrl = url;
                            setSession(currentUrl);
                            if (!isOnline) {
                                handler.sendEmptyMessage(HIDE_PROGRESS);
                                handler.sendEmptyMessage(STOP_LOADING);
                            } else {
                                String pageType = "application/html";
                                if (!url.contains("vk.com")) {
                                    getPageType(url);
                                }
                                if (pageType.contains("application")
                                        && !pageType.contains("html")
                                        && !pageType.contains("xml")) {
                                    startActivityForResult(
                                            new Intent(Intent.ACTION_VIEW,
                                                    Uri.parse(url)), DOWNLOAD_REQUEST_CODE);
                                    return super.shouldOverrideUrlLoading(view, url);
                                } else {
                                    view.getSettings().setLoadWithOverviewMode(true);
                                    view.getSettings().setUseWideViewPort(true);
                                    view.setBackgroundColor(Color.WHITE);
                                }
                            }
                            return false;
                        }

                    } catch (Exception ex) { // Error Logging
                        return false;
                    }
                }
            });

            handler.sendEmptyMessage(SHOW_PROGRESS);

            new Thread() {
                @Override
                public void run() {


                    EntityParser parser;
                    if (widget.getPluginXmlData() != null) {
                        if (widget.getPluginXmlData().length() > 0) {
                            parser = new EntityParser(widget.getPluginXmlData());
                        } else {
                            String xmlData = readXmlFromFile(getIntent().getStringExtra("WidgetFile"));
                            parser = new EntityParser(xmlData);
                        }
                    } else {
                        String xmlData = readXmlFromFile(getIntent().getStringExtra("WidgetFile"));
                        parser = new EntityParser(xmlData);
                    }

                    parser.parse();

                    url = parser.getUrl();
                    html = parser.getHtml();

                    if (url.length() > 0 && !isOnline) {
                        handler.sendEmptyMessage(NEED_INTERNET_CONNECTION);
                    } else {
                        if (isOnline) {
                        } else {
                            if (html.length() == 0) {
                            }
                        }
                        if (html.length() > 0 || url.length() > 0) {
                            handler.sendEmptyMessageDelayed(SHOW_HTML, 700);
                        } else {
                            handler.sendEmptyMessage(HIDE_PROGRESS);
                            handler.sendEmptyMessage(INITIALIZATION_FAILED);
                        }
                    }
                }
            }.start();

        } catch (Exception ex) {
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void destroy() {
        handler.sendEmptyMessage(HIDE_PROGRESS);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            closeFullScreenVideo();
        } else {

            //super.onBackPressed();
            finish();
        }
    }

    /**
     * Closes the HTML5 video if it is playing on full screen.
     */
    private void closeFullScreenVideo() {
        // Hide the custom view.  
        customView.setVisibility(View.GONE);
        // Remove the custom view from its container.  
        root.removeView(customView);
        customView = null;
        customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        // Show the content view.  
        webView.setVisibility(View.VISIBLE);
    }

    /* PRIVATE METHODS */
    private void showProgress() {
        if (state == states.LOAD_START) {
            state = states.LOAD_PROGRESS;
        }

        if (progressDialog == null || !progressDialog.isShowing()) {
            progressDialog = ProgressDialog.show(this, null, getString(R.string.romanblack_html_loading), true);
            progressDialog.setCancelable(false);
            progressDialog.setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    handler.sendEmptyMessage(LOADING_ABORTED);
                }
            });
        }
    }

    private void hideProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
        }
        state = states.EMPTY;
    }

    /**
     * Prepare and load data to WebView.
     */
    private void showHtml() {
        try {

            if (isOnline) {


                if (currentUrl.length() > 0 && !currentUrl.equals("about:blank")) {
                    url = currentUrl;
                }
                if (url.length() > 0)
                    html = "<html><body><a href=\"" + url + "\" id=\"link\" /></body></html>";

                Document doc = Jsoup.parse(html);
                Element iframe = doc.select("iframe").first();

                boolean isGoogleCalendar = false;
                boolean isGoogleForms = false;
                String iframeSrc = "";
                try {
                    if (iframe != null) {
                        iframeSrc = iframe.attr("src");
                    }
                } catch (Exception e) {
                }
                if (iframeSrc.length() > 0) {
                    isGoogleCalendar = iframeSrc.contains("www.google.com/calendar") || iframeSrc.contains("calendar.google.com/calendar");
                    isGoogleForms = iframeSrc.contains("google.com/forms");
                }
                if (isGoogleCalendar) {
                    webView.loadUrl(iframeSrc);
                } else if (isGoogleForms) {
                    webView.getSettings().setBuiltInZoomControls(false);

                    DisplayMetrics metrix = getResources().getDisplayMetrics();
                    int width = metrix.widthPixels;
                    int height = metrix.heightPixels;
                    float density = metrix.density;

                    iframe.attr("width", (int) (width / density) + "");
                    iframe.attr("height", (int) (height / density - (75 /*+ (hasAdView() ? 50 : 0)*/)) + "");

                    iframe.attr("style", "margin: 0; padding: 0");

                    Element body = doc.select("body").first();
                    body.attr("style", "margin: 0; padding: 0");

                    html = doc.outerHtml();

                    webView.loadDataWithBaseURL("http://", html, "text/html", "utf-8", "");
                } else {
                    Elements forms = doc.select("form");
                    Iterator<Element> iterator = forms.iterator();
                    for (; iterator.hasNext(); ) {
                        Element form = iterator.next();
                        String action = form.attr("action");

                        if (action.contains("paypal.com")) {
                            form.append("<input type=\"hidden\" name=\"bn\" value=\"ibuildapp_SP\">");
                        }

                        html = doc.html();
                    }

                    hideProgress = true;

                    if (Build.VERSION.SDK_INT>= 20 && html.contains("ibuildapp") && html.contains("powr")) {
                        int height = getResources().getDisplayMetrics().heightPixels;
                        html =  "<iframe width=\""+420+"\" height=\""+height+"\"  frameBorder=\"0\" src="+ url + "></iframe>";
                        webView.loadData(html, "text/html", "utf-8");
                    }
                    else
                    webView.loadDataWithBaseURL("http://", html, "text/html", "utf-8", "");
                }
            } else {
                if (html.length() > 0) {
                    webView.loadDataWithBaseURL("http://", html, "text/html", "utf-8", "");
                }
            }

            handler.sendEmptyMessageDelayed(HIDE_PROGRESS, 10000);

        } catch (Exception ex) { // Error Logging
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        switch (requestCode) {
            case DOWNLOAD_REQUEST_CODE: {
            }
            break;
            case DOWNLOAD_REQUEST_CODE_WITHOUT_START: {
                finish();
            }
            break;
            case FILECHOOSER_RESULTCODE: {
                if ( data == null) {
                    nullValueHandler();
                } else if (!isV21)
                    processResult(data, resultCode);
                else
                    processResultV21(data, requestCode);
                break;
            }
        }
       // super.onActivityResult(requestCode, resultCode, data);
    }

    private void processResultV21(Intent data, int requestCode) {
        String dataString = data.getDataString();
        Uri[] results = new Uri[]{Uri.parse(dataString)};
        mUploadMessageV21.onReceiveValue(results);
        mUploadMessageV21 = null;
        return;
    }

    private void nullValueHandler(){
        if (null == mUploadMessageV21)
            nullValueHandler();

        if (isV21){
            mUploadMessageV21.onReceiveValue(null);
            mUploadMessageV21 = null;
            isV21 = false;
        }
        else {
            mUploadMessage.onReceiveValue(null);
            mUploadMessage = null;
            isMedia  = false;
        }
    }
    public void processResult(Intent data, int resultCode){
        if (null == mUploadMessage)
            nullValueHandler();

        Uri result = data == null || resultCode != RESULT_OK ? null
                : data.getData();

        String filePath = result.getPath();

        Uri fileUri = Uri.fromFile(new File(filePath));
            if (isMedia) {
                ContentResolver cR = WebPlugin.this.getContentResolver();
                MimeTypeMap mime = MimeTypeMap.getSingleton();
                String type = mime.getExtensionFromMimeType(cR.getType(result));
                fileUri = Uri.parse(fileUri.toString() + "." + type);

                data.setData(fileUri);
                isMedia = false;
            }

            mUploadMessage.onReceiveValue(fileUri);
            mUploadMessage = null;
    }
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    /**
     * This menu contains back, forward and refresh buttons.
     *
     * @param menu
     * @return true if there are no an exception while preparing, false otherwise
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        try {
            menu.clear();
            MenuItem itemBack = menu.add("");
            itemBack.setIcon(R.drawable.romanblack_html_menu_back);
            itemBack.setOnMenuItemClickListener(
                    new MenuItem.OnMenuItemClickListener() {
                        public boolean onMenuItemClick(MenuItem mi) {
                            webView.goBack();
                            return true;
                        }
                    });
            if (!webView.canGoBack()) {
                itemBack.setEnabled(false);
            }

            MenuItem itemRefresh = menu.add("");
            itemRefresh.setIcon(R.drawable.romanblack_html_menu_refresh);
            itemRefresh.setOnMenuItemClickListener(
                    new MenuItem.OnMenuItemClickListener() {
                        public boolean onMenuItemClick(MenuItem mi) {
                            webView.reload();
                            return true;
                        }
                    });
            if ("".equals(currentUrl) || "about:blank".equals(currentUrl)) {
                itemRefresh.setEnabled(false);
            }

            MenuItem itemForward = menu.add("");
            itemForward.setIcon(R.drawable.romanblack_html_menu_forward);
            itemForward.setOnMenuItemClickListener(
                    new MenuItem.OnMenuItemClickListener() {
                        public boolean onMenuItemClick(MenuItem mi) {
                            webView.goForward();
                            return true;
                        }
                    });
            if (!webView.canGoForward()) {
                itemForward.setEnabled(false);
            }

            return true;

        } catch (Exception ex) {
            return false;
        }
    }

    public void closeActivity() {
        handler.sendEmptyMessage(HIDE_PROGRESS);
        finish();
    }

    /**
     * Returns Web resource mime type.
     *
     * @param url - web resource url
     * @return mime type of url resource
     */
    private String getPageType(String url) {
        try {
            String pageType = "page";
            try {
                HttpClient httpclient = new DefaultHttpClient();
                HttpResponse response = httpclient.execute(new HttpGet(url));
                HttpEntity entity = response.getEntity();
                try {
                    pageType = entity.getContentType().getValue();
                } catch (NullPointerException nPEx) {
                    Log.d("", "");
                }
                Log.w("", "");
            } catch (IOException iOEx) {
            } catch (Exception ex) {
                Log.e("", ex.getMessage());
            }
            return pageType;
        } catch (Exception ex) {
            return null;
        }
    }

    private enum states {

        EMPTY, LOAD_START, LOAD_PROGRESS, LOAD_COMPLETE
    }
}

/**
 * Using to show HTML page correctly any case
 */
class ObservableWebView extends WebView {

    private OnScrollChangedCallback mOnScrollChangedCallback;

    public ObservableWebView(final Context context) {
        super(context);
    }

    public ObservableWebView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    public ObservableWebView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onScrollChanged(final int l, final int t, final int oldl, final int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (mOnScrollChangedCallback != null) {
            mOnScrollChangedCallback.onScroll(l, t);
        }
    }

    /**
     * Redraws content to show it correctly
     */
    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);
        invalidate();
    }

    public OnScrollChangedCallback getOnScrollChangedCallback() {
        return mOnScrollChangedCallback;
    }

    public void setOnScrollChangedCallback(final OnScrollChangedCallback onScrollChangedCallback) {
        mOnScrollChangedCallback = onScrollChangedCallback;
    }

    public static interface OnScrollChangedCallback {

        public void onScroll(int l, int t);
    }

}