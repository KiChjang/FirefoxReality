/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.vrbrowser.ui.views;

import android.content.Context;
import android.content.res.Resources;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import org.mozilla.vrbrowser.R;
import org.mozilla.vrbrowser.VRBrowserApplication;
import org.mozilla.vrbrowser.audio.AudioEngine;
import org.mozilla.vrbrowser.browser.SessionStore;
import org.mozilla.vrbrowser.databinding.NavigationUrlBinding;
import org.mozilla.vrbrowser.db.entity.BookmarkEntity;
import org.mozilla.vrbrowser.model.Bookmark;
import org.mozilla.vrbrowser.search.SearchEngineWrapper;
import org.mozilla.vrbrowser.telemetry.TelemetryWrapper;
import org.mozilla.vrbrowser.ui.callbacks.NavigationBarCallback;
import org.mozilla.vrbrowser.utils.UrlUtils;
import org.mozilla.vrbrowser.viewmodel.BookmarkViewModel;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;

import androidx.annotation.StringRes;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.Observer;
import kotlin.Unit;
import mozilla.components.browser.domains.DomainAutoCompleteProvider;
import mozilla.components.ui.autocomplete.InlineAutocompleteEditText;

public class NavigationURLBar extends FrameLayout {

    private InlineAutocompleteEditText mURL;
    private ImageButton mMicrophoneButton;
    private ImageView mInsecureIcon;
    private ImageView mLoadingView;
    private Animation mLoadingAnimation;
    private RelativeLayout mURLLeftContainer;
    private boolean mIsLoading = false;
    private boolean mIsInsecure = false;
    private int mDefaultURLLeftPadding = 0;
    private int mURLProtocolColor;
    private int mURLWebsiteColor;
    private NavigationURLBarDelegate mDelegate;
    private DomainAutoCompleteProvider mAutocompleteProvider;
    private ImageButton mBookmarkButton;
    private VRBrowserApplication mApplication;
    private NavigationUrlBinding mBinding;
    private BookmarkViewModel mBookmarkModel;
    private AudioEngine mAudio;
    private boolean mIsBookmarkMode;

    private Unit domainAutocompleteFilter(String text, InlineAutocompleteEditText view) {
        if (view != null) {
            DomainAutoCompleteProvider.Result result = mAutocompleteProvider.autocomplete(text);
            view.applyAutocompleteResult(new InlineAutocompleteEditText.AutocompleteResult(
                    result.getText(),
                    result.getSource(),
                    result.getSize(),
                    null));
        }
        return Unit.INSTANCE;
    }

    public interface NavigationURLBarDelegate {
        void OnVoiceSearchClicked();
        void OnShowSearchPopup();
        void onHideSearchPopup();
    }

    public NavigationURLBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize(context);
    }

    private void initialize(Context aContext) {
        mApplication = (VRBrowserApplication)getContext().getApplicationContext();

        mAudio = AudioEngine.fromContext(aContext);

        // Inflate this data binding layout
        LayoutInflater inflater = LayoutInflater.from(aContext);
        mBinding = DataBindingUtil.inflate(inflater, R.layout.navigation_url, this, true);

        // Use Domain autocomplete provider from components
        mAutocompleteProvider = new DomainAutoCompleteProvider();
        mAutocompleteProvider.initialize(aContext, true, true, true);

        mURL = findViewById(R.id.urlEditText);
        mURL.setShowSoftInputOnFocus(false);
        mURL.setOnEditorActionListener((aTextView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleURLEdit(aTextView.getText().toString());
                return true;
            }
            return false;
        });

        mURL.setOnFocusChangeListener((view, focused) -> {
            showVoiceSearch(!focused || (mURL.getText().length() == 0));

            mURL.setSelection(mURL.getText().length(), 0);
        });

        final GestureDetector gd = new GestureDetector(getContext(), new UrlGestureListener());
        gd.setOnDoubleTapListener(mUrlDoubleTapListener);
        mURL.setOnTouchListener((view, motionEvent) -> {
            if (gd.onTouchEvent(motionEvent)) {
                return true;
            }
            return view.onTouchEvent(motionEvent);
        });
        mURL.addTextChangedListener(mURLTextWatcher);

        // Set a filter to provide domain autocomplete results
        mURL.setOnFilterListener(this::domainAutocompleteFilter);

        mURL.setFocusable(true);
        mURL.setFocusableInTouchMode(true);

        mMicrophoneButton = findViewById(R.id.microphoneButton);
        mMicrophoneButton.setTag(R.string.view_id_tag, R.id.microphoneButton);
        mMicrophoneButton.setOnClickListener(mMicrophoneListener);
        mURLLeftContainer = findViewById(R.id.urlLeftContainer);
        mInsecureIcon = findViewById(R.id.insecureIcon);
        mLoadingView = findViewById(R.id.loadingView);
        mLoadingAnimation = AnimationUtils.loadAnimation(aContext, R.anim.loading);
        mDefaultURLLeftPadding = mURL.getPaddingLeft();

        TypedValue typedValue = new TypedValue();
        Resources.Theme theme = aContext.getTheme();
        theme.resolveAttribute(R.attr.urlProtocolColor, typedValue, true);
        mURLProtocolColor = typedValue.data;
        theme.resolveAttribute(R.attr.urlWebsiteColor, typedValue, true);
        mURLWebsiteColor = typedValue.data;

        // Bookmarks
        mBookmarkButton = findViewById(R.id.bookmarkButton);
        mBookmarkButton.setSoundEffectsEnabled(false);

        setURL("");
        mIsBookmarkMode = false;

        // Prevent the URL TextEdit to get focus when user touches something outside of it
        setFocusable(true);
        setClickable(true);
        syncViews();
    }

    public void onPause() {
        if (mIsLoading) {
            mLoadingView.clearAnimation();
        }
    }

    public void onResume() {
        if (mIsLoading) {
            mLoadingView.startAnimation(mLoadingAnimation);
        }

    }

    public void setDelegate(NavigationURLBarDelegate delegate) {
        mDelegate = delegate;
    }

    public void setIsBookmarkMode(boolean isBookmarkMode) {
        mIsBookmarkMode = isBookmarkMode;
        syncViews();
    }

    private final NavigationBarCallback mNavigationBarCallback = new NavigationBarCallback() {
        @Override
        public void onBookmarkClick(Bookmark bookmark) {
            if (mAudio != null) {
                mAudio.playSound(AudioEngine.Sound.CLICK);
            }

            if (bookmark == null) {
                BookmarkEntity bookmarkEntity = new BookmarkEntity(
                        SessionStore.get().getCurrentTitle(),
                        SessionStore.get().getCurrentUri(),
                        System.currentTimeMillis());
                mApplication.getRepository().insertBookmark(bookmarkEntity);

            } else {
                mApplication.getRepository().deleteBookmarkByUrl(bookmark.getUrl());
            }
        }
    };

    private Observer<BookmarkEntity> mBookmarkObserver = bookmarkEntity -> mBookmarkModel.setBookmark(bookmarkEntity);

    public void setHint(@StringRes int aHint) {
        mURL.setHint(aHint);
    }

    public void setURL(String aURL) {
        if (mIsBookmarkMode) {
            return;
        }

        // Setup model view for this URL
        if (mBookmarkModel != null) {
            mBookmarkModel.getObservableBookmark().removeObserver(mBookmarkObserver);
        }
        mBookmarkModel = new BookmarkViewModel(mApplication, mApplication.getRepository(), aURL);
        mBinding.setBookmarkViewModel(mBookmarkModel);
        mBinding.setCallback(mNavigationBarCallback);
        mBookmarkModel.getObservableBookmark().observeForever(mBookmarkObserver);

        mURL.removeTextChangedListener(mURLTextWatcher);

        int index = -1;
        if (aURL != null) {
            try {
                aURL = URLDecoder.decode(aURL, "UTF-8");

            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            if (aURL.startsWith("jar:"))
                return;
            else if (aURL.startsWith("resource:") || SessionStore.get().isHomeUri(aURL))
                aURL = "";
            else if (aURL.startsWith("data:") && SessionStore.get().isCurrentSessionPrivate())
                aURL = "";
            else
                index = aURL.indexOf("://");

            // Update the URL bar only if the URL is different than the current one and
            // the URL bar is not focused to avoid override user input
            if (!mURL.getText().toString().equalsIgnoreCase(aURL) && !mURL.isFocused()) {
                mURL.setText(aURL);
                if (index > 0) {
                    SpannableString spannable = new SpannableString(aURL);
                    ForegroundColorSpan color1 = new ForegroundColorSpan(mURLProtocolColor);
                    ForegroundColorSpan color2 = new ForegroundColorSpan(mURLWebsiteColor);
                    spannable.setSpan(color1, 0, index + 3, 0);
                    spannable.setSpan(color2, index + 3, aURL.length(), 0);
                    mURL.setText(spannable);

                } else {
                    mURL.setText(aURL);
                }
            }
        }

        mURL.addTextChangedListener(mURLTextWatcher);
    }

    public String getText() {
        return mURL.getText().toString();
    }

    public String getOriginalText() {
        try {
            return mURL.getOriginalText();

        } catch (IndexOutOfBoundsException e) {
            return mURL.getNonAutocompleteText();
        }
    }

    public void setIsInsecure(boolean aIsInsecure) {
        if (mIsInsecure != aIsInsecure) {
            mIsInsecure = aIsInsecure;
            syncViews();
        }
    }

    public void setIsLoading(boolean aIsLoading) {
        if (mIsLoading != aIsLoading) {
            mIsLoading = aIsLoading;
            if (mIsLoading) {
                mLoadingView.startAnimation(mLoadingAnimation);
            } else {
                mLoadingView.clearAnimation();
            }
            syncViews();
        }
    }

    public void setBookmarks(boolean enabled) {
        if (enabled) {
            mMicrophoneButton.setVisibility(GONE);
            mBookmarkButton.setVisibility(GONE);

        } else {
            mMicrophoneButton.setVisibility(VISIBLE);
            mBookmarkButton.setVisibility(VISIBLE);
        }
    }

    public void showVoiceSearch(boolean enabled) {
        if (enabled) {
            mMicrophoneButton.setImageResource(R.drawable.ic_icon_microphone);
            mMicrophoneButton.setBackgroundResource(R.drawable.url_button);
            mMicrophoneButton.getLayoutParams().width = (int)getContext().getResources().getDimension(R.dimen.url_bar_item_width);
            mMicrophoneButton.setOnClickListener(mMicrophoneListener);

            if (mIsBookmarkMode) {
                mMicrophoneButton.setVisibility(GONE);

            } else {
                mBookmarkButton.setVisibility(VISIBLE);
            }

        } else if (mURL.hasFocus()){
            mMicrophoneButton.setImageResource(R.drawable.ic_icon_clear);
            mMicrophoneButton.setBackgroundResource(R.drawable.url_button_end);
            mMicrophoneButton.getLayoutParams().width = (int)getContext().getResources().getDimension(R.dimen.url_bar_last_item_width);
            mMicrophoneButton.setOnClickListener(mClearListener);

            if (mIsBookmarkMode) {
                mMicrophoneButton.setVisibility(VISIBLE);
            }

            mBookmarkButton.setVisibility(GONE);
        }
    }

    private void syncViews() {
        boolean showContainer = (mIsInsecure || mIsLoading) && !mIsBookmarkMode;
        int leftPadding = mDefaultURLLeftPadding;
        if (showContainer) {
            mURLLeftContainer.setVisibility(View.VISIBLE);
            mURLLeftContainer.measure(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            mLoadingView.setVisibility(mIsLoading ? View.VISIBLE : View.GONE);
            mInsecureIcon.setVisibility(!mIsLoading && mIsInsecure ? View.VISIBLE : View.GONE);
            leftPadding = mURLLeftContainer.getMeasuredWidth();
        }
        else {
            mURLLeftContainer.setVisibility(View.GONE);
            mLoadingView.setVisibility(View.GONE);
            mInsecureIcon.setVisibility(View.GONE);
        }

        mURL.setPadding(leftPadding, mURL.getPaddingTop(), mURL.getPaddingRight(), mURL.getPaddingBottom());
    }

    public  void handleURLEdit(String text) {
        text = text.trim();
        URI uri = null;
        try {
            boolean hasProtocol = text.contains("://");
            String urlText = text;
            // Detect when the protocol is missing from the URL.
            // Look for a separated '.' in the text with no white spaces.
            if (!hasProtocol && !urlText.contains(" ") && UrlUtils.isDomain(urlText)) {
                urlText = "https://" + urlText;
                hasProtocol = true;
            }
            if (hasProtocol) {
                URL url = new URL(urlText);
                uri = url.toURI();
            }
        }
        catch (Exception ex) {
        }

        String url;
        if (uri != null) {
            url = uri.toString();
            TelemetryWrapper.urlBarEvent(true);
        } else if (text.startsWith("about:") || text.startsWith("resource://")) {
            url = text;
        } else {
            url = SearchEngineWrapper.get(getContext()).getSearchURL(text);

            // Doing search in the URL bar, so sending "aIsURL: false" to telemetry.
            TelemetryWrapper.urlBarEvent(false);
        }

        if (SessionStore.get().getCurrentUri() != url) {
            SessionStore.get().loadUri(url);

            if (mDelegate != null) {
                mDelegate.onHideSearchPopup();
            }
        }

        showVoiceSearch(text.isEmpty());
    }

    public void setPrivateMode(boolean isEnabled) {
        if (isEnabled)
            mURL.setBackground(getContext().getDrawable(R.drawable.url_background_private));
        else
            mURL.setBackground(getContext().getDrawable(R.drawable.url_background));
    }

    @Override
    public void setClickable(boolean clickable) {
        super.setClickable(clickable);
        mURL.setEnabled(clickable);
    }

    private OnClickListener mMicrophoneListener = view -> {
        if (mAudio != null) {
            mAudio.playSound(AudioEngine.Sound.CLICK);
        }

        view.requestFocusFromTouch();
        if (mDelegate != null)
            mDelegate.OnVoiceSearchClicked();

        TelemetryWrapper.voiceInputEvent();
    };

    private OnClickListener mClearListener = view -> {
        if (mAudio != null) {
            mAudio.playSound(AudioEngine.Sound.CLICK);
        }

        mURL.getText().clear();
    };

    private TextWatcher mURLTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            if (mURL.getText().length() > 0) {
                showVoiceSearch(false);

            } else {
                showVoiceSearch(true);
            }
        }

        @Override
        public void afterTextChanged(Editable editable) {
            if (mDelegate != null) {
                mDelegate.OnShowSearchPopup();
            }
        }
    };

    private class UrlGestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onDoubleTap(MotionEvent event) {
            return true;
        }
    }

    GestureDetector.OnDoubleTapListener mUrlDoubleTapListener = new GestureDetector.OnDoubleTapListener() {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent motionEvent) {
            return false;
        }

        @Override
        public boolean onDoubleTap(MotionEvent motionEvent) {
            return false;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent motionEvent) {
            mURL.setSelection(mURL.getText().length(), 0);
            return true;
        }
    };

}
