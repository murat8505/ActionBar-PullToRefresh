/*
 * Copyright 2013 Chris Banes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.senab.actionbarpulltorefresh.library;

import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * FIXME
 */
public class PullToRefreshAttacher implements View.OnTouchListener {

    /* Default configuration values */
    private static final int DEFAULT_HEADER_LAYOUT = R.layout.default_header;
    private static final float DEFAULT_REFRESH_SCROLL_DISTANCE = 0.5f;
    private static final boolean DEFAULT_REFRESH_ON_UP = false;
    private static final int DEFAULT_REFRESH_MINIMIZED_DELAY = 1 * 1000;
    private static final boolean DEFAULT_REFRESH_MINIMIZE = true;

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "PullToRefreshAttacher";

	/* Member Variables */

    private EnvironmentDelegate mEnvironmentDelegate;
    private HeaderTransformer mHeaderTransformer;

    private Activity mActivity;
    private View mHeaderView;
    private FrameLayout mHeaderViewWrapper;
    private HeaderViewListener mHeaderViewListener;

    private final int mTouchSlop;
    private final float mRefreshScrollDistance;

    private float mInitialMotionY, mLastMotionY, mPullBeginY;
    private float mInitialMotionX;
    private boolean mIsBeingDragged, mIsRefreshing, mHandlingTouchEventFromDown;

    private final WeakHashMap<View, ViewParams> mRefreshableViews;

    private boolean mEnabled = true;
    private final boolean mRefreshOnUp;
    private final int mRefreshMinimizeDelay;
    private final boolean mRefreshMinimize;
    private boolean mIsDestroyed = false;

    private final Handler mHandler = new Handler();

    /**
     * Get a PullToRefreshAttacher for this Activity. If there is already a
     * PullToRefreshAttacher attached to the Activity, the existing one is
     * returned, otherwise a new instance is created. This version of the method
     * will use default configuration options for everything.
     *
     * @param activity
     *            Activity to attach to.
     * @return PullToRefresh attached to the Activity.
     */
    public static PullToRefreshAttacher get(Activity activity) {
        return get(activity, new Options());
    }

    /**
     * Get a PullToRefreshAttacher for this Activity. If there is already a
     * PullToRefreshAttacher attached to the Activity, the existing one is
     * returned, otherwise a new instance is created.
     *
     * @param activity
     *            Activity to attach to.
     * @param options
     *            Options used when creating the PullToRefreshAttacher.
     * @return PullToRefresh attached to the Activity.
     */
    public static PullToRefreshAttacher get(Activity activity, Options options) {
        return new PullToRefreshAttacher(activity, options);
    }

    protected PullToRefreshAttacher(Activity activity, Options options) {
        if (options == null) {
            Log.i(LOG_TAG, "Given null options so using default options.");
            options = new Options();
        }

        mActivity = activity;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            // Register ActivityListener so that we can make sure we're destroyed
            ActivityListener.register(this);
        }

        mRefreshableViews = new WeakHashMap<View, ViewParams>();

        // Copy necessary values from options
        mRefreshScrollDistance = options.refreshScrollDistance;
        mRefreshOnUp = options.refreshOnUp;
        mRefreshMinimizeDelay = options.refreshMinimizeDelay;
        mRefreshMinimize = options.refreshMinimize;

        // EnvironmentDelegate
        mEnvironmentDelegate = options.environmentDelegate != null ? options.environmentDelegate
                : createDefaultEnvironmentDelegate();

        // Header Transformer
        mHeaderTransformer = options.headerTransformer != null ? options.headerTransformer
                : createDefaultHeaderTransformer();

        // Get touch slop for use later
        mTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        // Get Window Decor View
        final ViewGroup decorView = (ViewGroup) activity.getWindow()
                .getDecorView();

        // Create Header view and then add to Decor View
        mHeaderView = LayoutInflater.from(
                mEnvironmentDelegate.getContextForInflater(activity)).inflate(
                options.headerLayout, decorView, false);
        if (mHeaderView == null) {
            throw new IllegalArgumentException("Must supply valid layout id for header.");
        }
        // Make Header View invisible so it still gets a layout pass
        mHeaderView.setVisibility(View.INVISIBLE);

        mHeaderViewWrapper = new FrameLayout(mActivity);
        mHeaderViewWrapper.addView(mHeaderView);

        // Notify transformer
        mHeaderTransformer.onViewCreated(activity, mHeaderView);
        // TODO Remove the follow deprecated method call before v1.0
        mHeaderTransformer.onViewCreated(mHeaderView);

        // Now HeaderView to Activity
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (decorView.getWindowToken() != null) {
                    // The Decor View has a Window Token, so we can add the HeaderView!
                    addHeaderViewToActivity(mHeaderViewWrapper, mActivity);
                } else {
                    // The Decor View doesn't have a Window Token yet, post ourselves again...
                    mHandler.post(this);
                }
            }
        });
    }

    /**
     * Add a view which will be used to initiate refresh requests and a listener
     * to be invoked when a refresh is started. This version of the method will
     * try to find a handler for the view from the built-in view delegates.
     *
     * @param view
     *            View which will be used to initiate refresh requests.
     * @param refreshListener
     *            Listener to be invoked when a refresh is started.
     */
    public void addRefreshableView(View view, OnRefreshListener refreshListener) {
        addRefreshableView(view, null, refreshListener);
    }

    /**
     * Add a view which will be used to initiate refresh requests, along with a
     * delegate which knows how to handle the given view, and a listener to be
     * invoked when a refresh is started.
     *
     * @param view
     *            View which will be used to initiate refresh requests.
     * @param viewDelegate
     *            delegate which knows how to handle <code>view</code>.
     * @param refreshListener
     *            Listener to be invoked when a refresh is started.
     */
    public void addRefreshableView(View view, ViewDelegate viewDelegate,
            OnRefreshListener refreshListener) {
        addRefreshableView(view, viewDelegate, refreshListener, true);
    }

    /**
     * Add a view which will be used to initiate refresh requests, along with a
     * delegate which knows how to handle the given view, and a listener to be
     * invoked when a refresh is started.
     *
     * @param view
     *            View which will be used to initiate refresh requests.
     * @param viewDelegate
     *            delegate which knows how to handle <code>view</code>.
     * @param refreshListener
     *            Listener to be invoked when a refresh is started.
     * @param setTouchListener
     *            Whether to set this as the
     *            {@link android.view.View.OnTouchListener}.
     */
    void addRefreshableView(View view, ViewDelegate viewDelegate,
            OnRefreshListener refreshListener, final boolean setTouchListener) {
        checkIfDestroyed();
        // Check to see if view is null
        if (view == null) {
            Log.i(LOG_TAG, "Refreshable View is null.");
            return;
        }

        if (refreshListener == null) {
            throw new IllegalArgumentException(
                    "OnRefreshListener not given. Please provide one.");
        }

        // ViewDelegate
        if (viewDelegate == null) {
            viewDelegate = InstanceCreationUtils.getBuiltInViewDelegate(view);
            if (viewDelegate == null) {
                throw new IllegalArgumentException(
                        "No view handler found. Please provide one.");
            }
        }

        // View to detect refreshes for
        mRefreshableViews.put(view, new ViewParams(viewDelegate, refreshListener));
        if (setTouchListener) {
            view.setOnTouchListener(this);
        }
    }

    /**
     * Remove a view which was previously used to initiate refresh requests.
     *
     * @param view
     *            - View which will be used to initiate refresh requests.
     */
    public void removeRefreshableView(View view) {
        checkIfDestroyed();
        if (mRefreshableViews.containsKey(view)) {
            mRefreshableViews.remove(view);
            view.setOnTouchListener(null);
        }
    }

    /**
     * Clear all views which were previously used to initiate refresh requests.
     */
    public void clearRefreshableViews() {
        checkIfDestroyed();
        Set<View> views = mRefreshableViews.keySet();
        for (View view : views) {
            view.setOnTouchListener(null);
        }
        mRefreshableViews.clear();
    }

    /**
     * This method should be called by your Activity's or Fragment's
     * onConfigurationChanged method.
     *
     * @param newConfig The new configuration
     */
    public void onConfigurationChanged(Configuration newConfig) {
        mHeaderTransformer.onConfigurationChanged(mActivity, newConfig);
    }

    /**
     * Manually set this Attacher's refreshing state. The header will be
     * displayed or hidden as requested.
     *
     * @param refreshing
     *            - Whether the attacher should be in a refreshing state,
     */
    public final void setRefreshing(boolean refreshing) {
        setRefreshingInt(null, refreshing, false);
    }

    /**
     * @return true if this Attacher is currently in a refreshing state.
     */
    public final boolean isRefreshing() {
        return mIsRefreshing;
    }

    /**
     * @return true if this PullToRefresh is currently enabled (defaults to
     *         <code>true</code>)
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Allows the enable/disable of this PullToRefreshAttacher. If disabled when
     * refreshing then the UI is automatically reset.
     *
     * @param enabled
     *            - Whether this PullToRefreshAttacher is enabled.
     */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;

        if (!enabled) {
            // If we're not enabled, reset any touch handling
            resetTouch();

            // If we're currently refreshing, reset the ptr UI
            if (mIsRefreshing) {
                reset(false);
            }
        }
    }

    /**
     * Call this when your refresh is complete and this view should reset itself
     * (header view will be hidden).
     *
     * This is the equivalent of calling <code>setRefreshing(false)</code>.
     */
    public final void setRefreshComplete() {
        setRefreshingInt(null, false, false);
    }

    /**
     * This should be called when you now longer need the Pull-to-Refresh functionality. Typically
     * from your {@link android.app.Activity#onDestroy()}.
     *
     * Please note, this is automatically when running on a device with Android v4.0 (Ice Cream Sandwich)
     * by the hosting Activity's {@link android.app.Activity#onDestroy() onDestroy()}.
     */
    public void destroy() {
        if (mIsDestroyed) return; // We've already been destroyed

        // Remove the Header View from the Activity
        removeHeaderViewFromActivity(mHeaderViewWrapper, mActivity);

        // Lets clear out all of our internal state
        clearRefreshableViews();
        mActivity = null;
        mHeaderView = mHeaderViewWrapper = null;
        mHeaderViewListener = null;
        mEnvironmentDelegate = null;
        mHeaderTransformer = null;

        mIsDestroyed = true;
    }

    /**
     * Set a {@link HeaderViewListener} which is called when the visibility
     * state of the Header View has changed.
     *
     * @param listener
     */
    public final void setHeaderViewListener(HeaderViewListener listener) {
        mHeaderViewListener = listener;
    }

    /**
     * @return The Header View which is displayed when the user is pulling, or
     *         we are refreshing.
     */
    public final View getHeaderView() {
        return mHeaderView;
    }

    /**
     * @return The HeaderTransformer currently used by this Attacher.
     */
    public HeaderTransformer getHeaderTransformer() {
        return mHeaderTransformer;
    }

    @Override
    public final boolean onTouch(final View view, final MotionEvent event) {
        // Just call onTouchEvent. It now handles the proper calling of onInterceptTouchEvent
        onTouchEvent(view, event);
        // Always return false as we only want to observe events
        return false;
    }

    final boolean onInterceptTouchEvent(View view, MotionEvent event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onInterceptTouchEvent: " + event.toString());
        }

        // If we're not enabled or currently refreshing don't handle any touch
        // events
        if (!isEnabled() || isRefreshing()) {
            return false;
        }

        final ViewParams params = mRefreshableViews.get(view);
        if (params == null) {
            return false;
        }

        if (DEBUG) Log.d(LOG_TAG, "onInterceptTouchEvent. Got ViewParams. " + view.toString());

        final float x = event.getX(), y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                // We're not currently being dragged so check to see if the user has
                // scrolled enough
                if (!mIsBeingDragged && mInitialMotionY > 0f) {
                    final float yDiff = y - mInitialMotionY;
                    final float xDiff = x - mInitialMotionX;

                    if (yDiff > xDiff && yDiff > mTouchSlop) {
                        mIsBeingDragged = true;
                        onPullStarted(y);
                    } else if (yDiff < -mTouchSlop) {
                        resetTouch();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_DOWN: {
                // If we're already refreshing, ignore
                if (canRefresh(true, params.getOnRefreshListener())
                        && params.getViewDelegate().isReadyForPull(view, x, y)) {
                    mInitialMotionX = x;
                    mInitialMotionY = y;
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                resetTouch();
                break;
            }
        }

        if (DEBUG) Log.d(LOG_TAG, "onInterceptTouchEvent. Returning " + mIsBeingDragged);

        return mIsBeingDragged;
    }

    final boolean onTouchEvent(View view, MotionEvent event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTouchEvent: " + event.toString());
        }

        // If we're not enabled or currently refreshing don't handle any touch
        // events
        if (!isEnabled()) {
            return false;
        }

        final ViewParams params = mRefreshableViews.get(view);
        if (params == null) {
            Log.i(LOG_TAG, "View does not have ViewParams");
            return false;
        }

        // Record whether our handling is started from ACTION_DOWN
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mHandlingTouchEventFromDown = true;
        }

        // If we're being called from ACTION_DOWN then we must call through to
        // onInterceptTouchEvent until it sets mIsBeingDragged
        if (mHandlingTouchEventFromDown && !mIsBeingDragged) {
            onInterceptTouchEvent(view, event);
            return true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                // If we're already refreshing ignore it
                if (isRefreshing()) {
                    return false;
                }

                final float y = event.getY();

                if (mIsBeingDragged && y != mLastMotionY) {
                    final float yDx = y - mLastMotionY;

                    /**
                     * Check to see if the user is scrolling the right direction
                     * (down). We allow a small scroll up which is the check against
                     * negative touch slop.
                     */
                    if (yDx >= -mTouchSlop) {
                        onPull(view, y);
                        // Only record the y motion if the user has scrolled down.
                        if (yDx > 0f) {
                            mLastMotionY = y;
                        }
                    } else {
                        onPullEnded();
                        resetTouch();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                checkScrollForRefresh(view);
                if (mIsBeingDragged) {
                    onPullEnded();
                }
                resetTouch();
                break;
            }
        }

        return true;
    }

    void resetTouch() {
        mIsBeingDragged = false;
        mHandlingTouchEventFromDown = false;
        mInitialMotionY = mLastMotionY = mPullBeginY = -1f;
    }

    void onPullStarted(float y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPullStarted");
        }
        showHeaderView();
        mPullBeginY = y;
    }

    void onPull(View view, float y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPull");
        }

        final float pxScrollForRefresh = getScrollNeededForRefresh(view);
        final float scrollLength = y - mPullBeginY;

        if (scrollLength < pxScrollForRefresh) {
            mHeaderTransformer.onPulled(scrollLength / pxScrollForRefresh);
        } else {
            if (mRefreshOnUp) {
                mHeaderTransformer.onReleaseToRefresh();
            } else {
                setRefreshingInt(view, true, true);
            }
        }
    }

    void onPullEnded() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPullEnded");
        }
        if (!mIsRefreshing) {
            reset(true);
        }
    }

    void showHeaderView() {
        if (mHeaderTransformer.showHeaderView()) {
            if (mHeaderViewListener != null) {
                mHeaderViewListener.onStateChanged(mHeaderView,
                        HeaderViewListener.STATE_VISIBLE);
            }
        }
    }

    void hideHeaderView() {
        if (mHeaderTransformer.hideHeaderView()) {
            if (mHeaderViewListener != null) {
                mHeaderViewListener.onStateChanged(mHeaderView,
                        HeaderViewListener.STATE_HIDDEN);
            }
        }
    }

    final Activity getAttachedActivity() {
        return mActivity;
    }

    protected EnvironmentDelegate createDefaultEnvironmentDelegate() {
        return new EnvironmentDelegate();
    }

    protected HeaderTransformer createDefaultHeaderTransformer() {
        return new DefaultHeaderTransformer();
    }

    private boolean checkScrollForRefresh(View view) {
        if (mIsBeingDragged && mRefreshOnUp && view != null) {
            if (mLastMotionY - mPullBeginY >= getScrollNeededForRefresh(view)) {
                setRefreshingInt(view, true, true);
                return true;
            }
        }
        return false;
    }

    private void setRefreshingInt(View view, boolean refreshing, boolean fromTouch) {
        checkIfDestroyed();

        if (DEBUG) Log.d(LOG_TAG, "setRefreshingInt: " + refreshing);
        // Check to see if we need to do anything
        if (mIsRefreshing == refreshing) {
            return;
        }

        resetTouch();

        if (refreshing && canRefresh(fromTouch, getRefreshListenerForView(view))) {
            startRefresh(view, fromTouch);
        } else {
            reset(fromTouch);
        }
    }

    private OnRefreshListener getRefreshListenerForView(View view) {
        if (view != null) {
            ViewParams params = mRefreshableViews.get(view);
            if (params != null) {
                return params.getOnRefreshListener();
            }
        }
        return null;
    }

    /**
     * @param fromTouch
     *            - Whether this is being invoked from a touch event
     * @return true if we're currently in a state where a refresh can be
     *         started.
     */
    private boolean canRefresh(boolean fromTouch, OnRefreshListener listener) {
        return !mIsRefreshing && (!fromTouch || listener != null);
    }

    private float getScrollNeededForRefresh(View view) {
        return view.getHeight() * mRefreshScrollDistance;
    }

    private void reset(boolean fromTouch) {
        // Update isRefreshing state
        mIsRefreshing = false;

        // Remove any minimize callbacks
        if (mRefreshMinimize) {
            mHandler.removeCallbacks(mRefreshMinimizeRunnable);
        }

        // Hide Header View
        hideHeaderView();
    }

    private void startRefresh(View view, boolean fromTouch) {
        // Update isRefreshing state
        mIsRefreshing = true;

        // Call OnRefreshListener if this call has originated from a touch event
        if (fromTouch) {
            OnRefreshListener listener = getRefreshListenerForView(view);
            if (listener != null) {
                listener.onRefreshStarted(view);
            }
        }

        // Call Transformer
        mHeaderTransformer.onRefreshStarted();

        // Show Header View
        showHeaderView();

        // Post a runnable to minimize the refresh header
        if (mRefreshMinimize) {
            if (mRefreshMinimizeDelay > 0) {
                mHandler.postDelayed(mRefreshMinimizeRunnable, mRefreshMinimizeDelay);
            } else {
                mHandler.post(mRefreshMinimizeRunnable);
            }
        }
    }

    private void checkIfDestroyed() {
        if (mIsDestroyed) {
            throw new IllegalStateException("PullToRefreshAttacher is destroyed.");
        }
    }

    protected void addHeaderViewToActivity(View headerViewLayout, Activity activity) {
        // Get the Display Rect of the Decor View
        final View decorView = activity.getWindow().getDecorView();
        final Rect visibleRect = new Rect();
        decorView.getWindowVisibleDisplayFrame(visibleRect);

        // Create LayoutParams for adding the View as a panel
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.x = 0;
        params.y = visibleRect.top;
        params.gravity = Gravity.TOP;

        activity.getWindowManager().addView(headerViewLayout, params);
    }

    protected void removeHeaderViewFromActivity(View headerViewLayout, Activity activity) {
        activity.getWindowManager().removeViewImmediate(headerViewLayout);
    }

    /**
     * Simple Listener to listen for any callbacks to Refresh.
     */
    public interface OnRefreshListener {
        /**
         * Called when the user has initiated a refresh by pulling.
         *
         * @param view
         *            - View which the user has started the refresh from.
         */
        public void onRefreshStarted(View view);
    }

    public interface HeaderViewListener {
        /**
         * The state when the header view is completely visible.
         */
        public static int STATE_VISIBLE = 0;

        /**
         * The state when the header view is minimized. By default this means
         * that the progress bar is still visible, but the rest of the view is
         * hidden, showing the Action Bar behind.
         * <p/>
         * This will not be called in header minimization is disabled.
         */
        public static int STATE_MINIMIZED = 1;

        /**
         * The state when the header view is completely hidden.
         */
        public static int STATE_HIDDEN = 2;

        /**
         * Called when the visibility state of the Header View has changed.
         *
         * @param headerView
         *            HeaderView who's state has changed.
         * @param state
         *            The new state. One of {@link #STATE_VISIBLE},
         *            {@link #STATE_MINIMIZED} and {@link #STATE_HIDDEN}
         */
        public void onStateChanged(View headerView, int state);
    }

    /**
     * HeaderTransformers are what controls and update the Header View to reflect the current state
     * of the pull-to-refresh interaction. They are responsible for showing and hiding the header
     * view, as well as update the state.
     */
    public static abstract class HeaderTransformer {

        /**
         * Called whether the header view has been inflated from the resources
         * defined in {@link Options#headerLayout}.
         *
         * @param activity The {@link Activity} that the header view is attached to.
         * @param headerView The inflated header view.
         */
        public void onViewCreated(Activity activity, View headerView) {}

        /**
         * @deprecated This will be removed before v1.0. Override
         * {@link #onViewCreated(android.app.Activity, android.view.View)} instead.
         */
        public void onViewCreated(View headerView) {}

        /**
         * Called when the header should be reset. You should update any child
         * views to reflect this.
         * <p/>
         * You should <strong>not</strong> change the visibility of the header
         * view.
         */
        public void onReset() {}

        /**
         * Called the user has pulled on the scrollable view.
         *
         * @param percentagePulled value between 0.0f and 1.0f depending on how far the
         *                         user has pulled.
         */
        public void onPulled(float percentagePulled) {}

        /**
         * Called when a refresh has begun. Theoretically this call is similar
         * to that provided from {@link OnRefreshListener} but is more suitable
         * for header view updates.
         */
        public void onRefreshStarted() {}

        /**
         * Called when a refresh can be initiated when the user ends the touch
         * event. This is only called when {@link Options#refreshOnUp} is set to
         * true.
         */
        public void onReleaseToRefresh() {}

        /**
         * Called when the current refresh has taken longer than the time
         * specified in {@link Options#refreshMinimizeDelay}.
         */
        public void onRefreshMinimized() {}

        /**
         * Called when the Header View should be made visible, usually with an animation.
         *
         * @return true if the visibility has changed.
         */
        public abstract boolean showHeaderView();

        /**
         * Called when the Header View should be made invisible, usually with an animation.
         *
         * @return true if the visibility has changed.
         */
        public abstract boolean hideHeaderView();

        /**
         * Called when the Activity's configuration has changed.
         *
         * @param activity The {@link Activity} that the header view is attached to.
         * @param newConfig New configuration.
         *
         * @see android.app.Activity#onConfigurationChanged(android.content.res.Configuration)
         */
        public void onConfigurationChanged(Activity activity, Configuration newConfig) {}
    }

    /**
     * ViewDelegates are what are used to de-couple the Attacher from the different types of
     * scrollable views.
     */
    public static abstract class ViewDelegate {

        /**
         * Allows you to provide support for View which do not have built-in
         * support. In this method you should cast <code>view</code> to it's
         * native class, and check if it is scrolled to the top.
         *
         * @param view
         *            The view which has should be checked against.
         * @param x The X co-ordinate of the touch event
         * @param y The Y co-ordinate of the touch event
         * @return true if <code>view</code> is scrolled to the top.
         */
        public abstract boolean isReadyForPull(View view, float x, float y);
    }

    /**
     * This is used to provide platform and environment specific functionality for the Attacher.
     */
    public static class EnvironmentDelegate {

        /**
         * @return Context which should be used for inflating the header layout
         */
        public Context getContextForInflater(Activity activity) {
            Context context = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                ActionBar ab = activity.getActionBar();
                if (ab != null) {
                    context = ab.getThemedContext();
                }
            }
            if (context == null) {
                context = activity;
            }
            return context;
        }
    }

    /**
     * Allows you to specify a number of configuration options when instantiating a
     * {@link PullToRefreshAttacher}. Used with {@link #get(Activity, Options) get()}.
     */
    public static class Options {

        /**
         * EnvironmentDelegate instance which will be used. If null, we will
         * create an instance of the default class.
         */
        public EnvironmentDelegate environmentDelegate = null;

        /**
         * The layout resource ID which should be inflated to be displayed above
         * the Action Bar
         */
        public int headerLayout = DEFAULT_HEADER_LAYOUT;

        /**
         * The header transformer to be used to transfer the header view. If
         * null, an instance of {@link DefaultHeaderTransformer} will be used.
         */
        public HeaderTransformer headerTransformer = null;

        /**
         * The percentage of the refreshable view that needs to be scrolled
         * before a refresh is initiated.
         */
        public float refreshScrollDistance = DEFAULT_REFRESH_SCROLL_DISTANCE;

        /**
         * Whether a refresh should only be initiated when the user has finished
         * the touch event.
         */
        public boolean refreshOnUp = DEFAULT_REFRESH_ON_UP;

        /**
         * The delay after a refresh is started in which the header should be
         * 'minimized'. By default, most of the header is faded out, leaving
         * only the progress bar signifying that a refresh is taking place.
         */
        public int refreshMinimizeDelay = DEFAULT_REFRESH_MINIMIZED_DELAY;

        /**
         * Enable or disable the header 'minimization', which by default means that the majority of
         * the header is hidden, leaving only the progress bar still showing.
         * <p/>
         * If set to true, the header will be minimized after the delay set in
         * {@link #refreshMinimizeDelay}. If set to false then the whole header will be displayed
         * until the refresh is finished.
         */
        public boolean refreshMinimize = DEFAULT_REFRESH_MINIMIZE;
    }

    private static final class ViewParams {
        private final WeakReference<OnRefreshListener> mOnRefreshListener;
        private final ViewDelegate mViewDelegate;

        ViewParams(ViewDelegate viewDelegate, OnRefreshListener onRefreshListener) {
            mOnRefreshListener = new WeakReference<OnRefreshListener>(onRefreshListener);
            mViewDelegate = viewDelegate;
        }

        OnRefreshListener getOnRefreshListener() {
            return mOnRefreshListener.get();
        }

        ViewDelegate getViewDelegate() {
            return mViewDelegate;
        }
    }

    private final Runnable mRefreshMinimizeRunnable = new Runnable() {
        @Override
        public void run() {
            mHeaderTransformer.onRefreshMinimized();

            if (mHeaderViewListener != null) {
                mHeaderViewListener.onStateChanged(mHeaderView,
                        HeaderViewListener.STATE_MINIMIZED);
            }
        }
    };

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    static class ActivityListener implements Application.ActivityLifecycleCallbacks {
        private final PullToRefreshAttacher mPullToRefreshAttacher;

        static void register(PullToRefreshAttacher pullToRefreshAttacher) {
            ActivityListener listener = new ActivityListener(pullToRefreshAttacher);
            pullToRefreshAttacher.getAttachedActivity().getApplication()
                    .registerActivityLifecycleCallbacks(listener);
        }

        private ActivityListener(PullToRefreshAttacher pullToRefreshAttacher) {
            mPullToRefreshAttacher = pullToRefreshAttacher;
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle bundle) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            Activity attacherActivity = mPullToRefreshAttacher.getAttachedActivity();
            if (attacherActivity == activity) {
                // Destroy PullToRefreshAttacher
                mPullToRefreshAttacher.destroy();
                // Activity has been destroyed so, remove this listener
                attacherActivity.getApplication().unregisterActivityLifecycleCallbacks(this);
            }
        }
    }
}
