package com.rnds;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.animation.Interpolator;
import android.widget.OverScroller;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.uimanager.PixelUtil;
import com.facebook.react.uimanager.events.NativeGestureUtil;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.facebook.react.views.scroll.ReactScrollViewHelper;
import com.facebook.react.views.scroll.ReactScrollViewHelper.HasScrollEventThrottle;
import androidx.core.view.VelocityTrackerCompat;
import com.facebook.react.views.view.ReactViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;

import androidx.interpolator.view.animation.LinearOutSlowInInterpolator;

public class DirectedScrollView extends ReactViewGroup implements HasScrollEventThrottle {
  private static final Interpolator SNAP_BACK_ANIMATION_INTERPOLATOR =
      new LinearOutSlowInInterpolator();

  private int animLength = 50;
  private int animPointer = 0;
  private ObjectAnimator[] animList = new ObjectAnimator[animLength];
  private float minimumZoomScale = 1.0f;
  private float maximumZoomScale = 1.0f;
  private boolean bounces = true;
  private boolean alwaysBounceVertical = false;
  private boolean alwaysBounceHorizontal = false;
  private boolean bouncesZoom = true;
  private boolean scrollEnabled = true;
  private boolean pinchGestureEnabled = true;
  private int swipeDownHeight = 400;

  private float pivotX;
  private float pivotY;
  private float scrollX;
  private float scrollY;
  private float startScrollX;
  private float startScrollY;
  private float startTouchX;
  private float startTouchY;
  private float scaleFactor = 1.0f;
  private float decelerationRate = 0.998f;
  private boolean isScaleInProgress;
  private boolean isScrollInProgress;
  private float touchSlop;
  private float lastPositionX, lastPositionY;
  private int minFlingVelocity;
  private int maxFlingVelocity;
  private VelocityTracker velocityTracker = VelocityTracker.obtain();
  private long animationDuration = 2000;

  private ScaleGestureDetector scaleDetector;

  private ReactContext reactContext;

  public DirectedScrollView(Context context) {
    super(context);

    initPinchGestureListeners(context);
    reactContext = (ReactContext)this.getContext();
    ViewConfiguration vc = ViewConfiguration.get(context);
    touchSlop = vc.getScaledTouchSlop();
    minFlingVelocity = vc.getScaledMinimumFlingVelocity();
    maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();

    anchorChildren();
  }

  @Override
  public boolean onInterceptTouchEvent(final MotionEvent motionEvent) {
    if (!scrollEnabled) {
      return false;
    }

    int action = motionEvent.getAction();

    if(action == MotionEvent.ACTION_DOWN) {
      if(animList.length > 0) {
        for (int i = 0; i < animList.length; i++) {
          if(!Objects.isNull(animList[i])) {
            // when client touches during scroll we should get current scroll position, to use as start of next scroll.
            animList[i].cancel();
            String propertyName = animList[i].getPropertyName();
            if (propertyName.equals("translationX")) {
              startScrollX = scrollX = (float) animList[i].getAnimatedValue(propertyName);
            }
            if (propertyName.equals("translationY")) {
              startScrollY = scrollY = (float) animList[i].getAnimatedValue(propertyName);
            }
            animList[i] = null;
          }
        }
      }
      animPointer = 0;
      if (isScrollInProgress) {
        return true;
      }
    }

    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      isScrollInProgress = false;
      isScaleInProgress = false;
      return false;
    }

    if (action == MotionEvent.ACTION_MOVE && isScrollInProgress) {
      return true;
    }

    if (super.onInterceptTouchEvent(motionEvent)) {
      return true;
    }

    switch (action) {
      case MotionEvent.ACTION_DOWN:
        lastPositionX = motionEvent.getX();
        lastPositionY = motionEvent.getY();
        onActionDown(motionEvent);
        break;
      case MotionEvent.ACTION_POINTER_DOWN:
        onActionPointerDown();
        break;
      case MotionEvent.ACTION_MOVE:
        float diffX = Math.abs(motionEvent.getX() - lastPositionX);
        float diffY = Math.abs(motionEvent.getY() - lastPositionY);
        if (isScaleInProgress || diffX > touchSlop || diffY > touchSlop) {
          ReactScrollViewHelper.emitScrollBeginDragEvent(this);
          isScrollInProgress = true;
          lastPositionX = motionEvent.getX();
          lastPositionY = motionEvent.getY();
          disallowInterceptTouchEventsForParent();
          return true;
        }
        break;
    }

    return false;
  }

  @Override
  public boolean onTouchEvent(MotionEvent motionEvent) {
    switch (motionEvent.getAction()) {
      case MotionEvent.ACTION_DOWN:
        onActionDown(motionEvent);
        break;
      case MotionEvent.ACTION_POINTER_DOWN:
        onActionPointerDown();
        break;
      case MotionEvent.ACTION_MOVE:
        float deltaX = motionEvent.getX() - startTouchX;
        float deltaY = motionEvent.getY() - startTouchY;
        if (Float.compare(deltaX, 0.0F) == 0 && Float.compare(deltaY, 0.0F) == 0) {
          return false;
        } else {
          onActionMove(motionEvent);
        }
        break;
      case MotionEvent.ACTION_UP:
        onActionUp();
        break;
    }

    scaleDetector.onTouchEvent(motionEvent);

    return true;
  }

  private void disallowInterceptTouchEventsForParent() {
    ViewParent parent = getParent();
    if (parent != null) {
      parent.requestDisallowInterceptTouchEvent(true);
    }
  }

  private void initPinchGestureListeners(Context context) {
    scaleDetector = new ScaleGestureDetector(
        context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
          @Override
          public boolean onScaleBegin(ScaleGestureDetector detector) {
            float x = detector.getFocusX();
            float y = detector.getFocusY();
            pivotChildren(x, y);
            updateChildren();
            return true;
          }

          @Override
          public boolean onScale(ScaleGestureDetector detector) {
            if (!pinchGestureEnabled) {
              return false;
            }

            scaleFactor *= detector.getScaleFactor();
            updateChildren();
            return true;
          }

          private void updateChildren() {
            if (bouncesZoom) {
              scaleChildren(false);
            } else {
              clampAndScaleChildren(false);
            }

            if (bounces) {
              translateChildren(false);
            } else {
              clampAndTranslateChildren(false);
            }
            invalidate();
          }
        });
  }

  private void onActionDown(MotionEvent motionEvent) {
    // reset tracker by touch start
    velocityTracker.obtain();
    startTouchX = motionEvent.getX();
    startTouchY = motionEvent.getY();
    startScrollX = scrollX;
    startScrollY = scrollY;
  }

  private void onActionPointerDown() {
    isScaleInProgress = true;
  }

  private void onActionMove(MotionEvent motionEvent) {
    NativeGestureUtil.notifyNativeGestureStarted(this, motionEvent);

    if (isScaleInProgress) {
      return;
    }

    isScrollInProgress = true;

    float deltaX = motionEvent.getX() - startTouchX;
    float deltaY = motionEvent.getY() - startTouchY;

    scrollX = startScrollX + deltaX;
    scrollY = startScrollY + deltaY;

    // register velocity
    velocityTracker.addMovement(motionEvent);
    if (bounces) {
      clampAndTranslateChildren(
          false,
          getMaxScrollY() <= 0 && !alwaysBounceVertical,
          getMaxScrollX() <= 0 && !alwaysBounceHorizontal);
    } else {
      clampAndTranslateChildren(false);
    }

    ReactScrollViewHelper.emitScrollEvent(this, deltaX * -1, deltaY * -1);
  }

  private void onActionUp() {
    // recalculate
    velocityTracker.computeCurrentVelocity(1);
    float scrollYSwipe = 0;
    if (isScrollInProgress) {
      float scale = getContext().getResources().getDisplayMetrics().density;
      float rnVelocityX = velocityTracker.getXVelocity();
      float rnVelocityY = velocityTracker.getYVelocity();
      float velocityX = rnVelocityX * scale * 100;
      float velocityY = rnVelocityY * scale * 100;

      scrollYSwipe = scrollY;
      ReactScrollViewHelper.emitScrollEndDragEvent(this, rnVelocityX, rnVelocityY);
      isScrollInProgress = false;

      if (Math.abs(velocityX) > minFlingVelocity || Math.abs(velocityY) > minFlingVelocity) {
        OverScroller scroller = predictFinalScrollPosition((int) velocityX, (int) velocityY);

        scrollX = velocityX + scroller.getFinalX();
        scrollY = velocityY + scroller.getFinalY();
        animationDuration = 1950;
      }
    }

    if (bounces) {
      clampAndTranslateChildren(true);
    }

    if (bouncesZoom) {
      clampAndScaleChildren(true);
    }

    isScaleInProgress = false;
    animationDuration = 250;

    if (scrollYSwipe !=0 && scrollYSwipe > this.swipeDownHeight) {
      System.out.println("onActionUp onSwipeDown height="+this.swipeDownHeight);
      this.reactContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
        .emit("onSwipeDown", null);
    }
  }

  private void clampAndTranslateChildren(boolean animated) {
    this.clampAndTranslateChildren(animated, true, true);
  }

  private void clampAndTranslateChildren(
      boolean animated,
      boolean clampVertical,
      boolean clampHorizontal) {
    float[] minPoints = transformPoints(new float[] {0, 0});
    float minX = minPoints[0];
    float minY = minPoints[1];
    float maxX = minPoints[0] + getMaxScrollX();
    float maxY = minPoints[1] + getMaxScrollY();

    if (clampHorizontal) {
      if (maxX > minX) {
        scrollX = clamp(scrollX, -maxX, -minX);
      } else {
        scrollX = -minX;
      }
    }
    if (clampVertical) {
      if (maxY > minY) {
        scrollY = clamp(scrollY, -maxY, -minY);
      } else {
        scrollY = -minY;
      }
    }
    translateChildren(animated);
  }

  private void clampAndScaleChildren(boolean animated) {
    scaleFactor = clamp(scaleFactor, minimumZoomScale, maximumZoomScale);

    scaleChildren(animated);
  }

  private void scaleChildren(boolean animated) {
    List<DirectedScrollViewChild> scrollableChildren = getScrollableChildren();

    for (DirectedScrollViewChild scrollableChild : scrollableChildren) {
      if (animated) {
        animateProperty(
            scrollableChild,
            "scaleX",
            scrollableChild.getScaleX(),
            scaleFactor);
        animateProperty(
            scrollableChild,
            "scaleY",
            scrollableChild.getScaleY(),
            scaleFactor);
      } else {
        scrollableChild.setScaleX(scaleFactor);
        scrollableChild.setScaleY(scaleFactor);
      }
    }
  }

  private void translateChildren(boolean animated) {
    List<DirectedScrollViewChild> scrollableChildren = getScrollableChildren();

    for (DirectedScrollViewChild scrollableChild : scrollableChildren) {
      if (scrollableChild.getShouldScrollHorizontally()) {
        if (animated) {
          animateProperty(
              scrollableChild,
              "translationX",
              scrollableChild.getTranslationX(),
              scrollX);
        } else {
          scrollableChild.setTranslationX(scrollX);
        }
      }

      if (scrollableChild.getShouldScrollVertically()) {
        if (animated) {
          animateProperty(
              scrollableChild,
              "translationY",
              scrollableChild.getTranslationY(),
              scrollY);
        } else {
          scrollableChild.setTranslationY(scrollY);
        }
      }
    }
  }

  private void pivotChildren(float newPivotX, float newPivotY) {
    float oldPivotX = pivotX;
    float oldPivotY = pivotY;
    pivotX = newPivotX - scrollX;
    pivotY = newPivotY - scrollY;

    scrollX += (oldPivotX - pivotX) * (1 - scaleFactor);
    scrollY += (oldPivotY - pivotY) * (1 - scaleFactor);

    List<DirectedScrollViewChild> scrollableChildren = getScrollableChildren();
    for (DirectedScrollViewChild scrollableChild : scrollableChildren) {
      if (scrollableChild.getShouldScrollHorizontally()) {
        scrollableChild.setTranslationX(scrollX);
        scrollableChild.setPivotX(pivotX);
      }
      if (scrollableChild.getShouldScrollVertically()) {
        scrollableChild.setTranslationY(scrollY);
        scrollableChild.setPivotY(pivotY);
      }
    }
  }

  private void anchorChildren() {
    List<DirectedScrollViewChild> scrollableChildren = getScrollableChildren();

    for (DirectedScrollViewChild scrollableChild : scrollableChildren) {
      scrollableChild.setPivotY(0);
      scrollableChild.setPivotX(0);
    }
  }

  private float[] transformPoints(float[] points) {
    float[] transformedPoints = new float[points.length];

    Matrix matrix = new Matrix();
    matrix.setScale(scaleFactor, scaleFactor, pivotX, pivotY);
    matrix.mapPoints(transformedPoints, points);

    return transformedPoints;
  }

  private void
  animateProperty(Object target, String property, float start, float end) {
    if (start == end) {
      return;
    }

    ObjectAnimator anim = ObjectAnimator.ofFloat(target, property, start, end);
    anim.setDuration(getAnimationDuration());
    anim.setInterpolator(SNAP_BACK_ANIMATION_INTERPOLATOR);
    anim.start();

    animList[animPointer] = anim;
    if(animPointer >= animLength)
      animPointer = 0;
    else
      animPointer++;
  }

  private float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(value, max));
  }

  private float getContentContainerWidth() {
    return getChildAt(0).getWidth() * scaleFactor;
  }

  private float getContentContainerHeight() {
    return getChildAt(0).getHeight() * scaleFactor;
  }

  private float getMaxScrollX() {
    return getContentContainerWidth() - getWidth();
  }

  private float getMaxScrollY() {
    return getContentContainerHeight() - getHeight();
  }

  private ArrayList<DirectedScrollViewChild> getScrollableChildren() {
    ArrayList<DirectedScrollViewChild> scrollableChildren = new ArrayList<>();

    for (int i = 0; i < getChildCount(); i++) {
      View childView = getChildAt(i);

      if (childView instanceof DirectedScrollViewChild) {
        scrollableChildren.add((DirectedScrollViewChild)childView);
      }
    }

    return scrollableChildren;
  }

  public void setMaximumZoomScale(final float maximumZoomScale) {
    this.maximumZoomScale = maximumZoomScale;
  }

  public void setMinimumZoomScale(final float minimumZoomScale) {
    this.minimumZoomScale = minimumZoomScale;
  }

  public void setPinchGestureEnabled(final boolean pinchGestureEnabled) {
    this.pinchGestureEnabled = pinchGestureEnabled;
  }

  public void setSwipeDownHeight(final int height) {
    this.swipeDownHeight = height;
  }

  public void setScrollEnabled(final boolean scrollEnabled) {
    this.scrollEnabled = scrollEnabled;
  }

  public void setBounces(final boolean bounces) {
    this.bounces = bounces;
  }

  public void setBouncesZoom(final boolean bouncesZoom) {
    this.bouncesZoom = bouncesZoom;
  }

  public void setAlwaysBounceHorizontal(final boolean alwaysBounceHorizontal) {
    this.alwaysBounceHorizontal = alwaysBounceHorizontal;
  }

  public void setAlwaysBounceVertical(final boolean alwaysBounceVertical) {
    this.alwaysBounceVertical = alwaysBounceVertical;
  }

  public void scrollTo(Double x, Double y, Boolean animated) {

    float convertedX = PixelUtil.toPixelFromDIP(x);
    float convertedY = PixelUtil.toPixelFromDIP(y);

    if (convertedY > getMaxScrollY()) {
      convertedY = getMaxScrollY();
    }
    if (convertedX > getMaxScrollX()) {
      convertedX = getMaxScrollX();
    }

    scrollX = -convertedX;
    scrollY = -convertedY;

    translateChildren(animated);
  }

  private OverScroller predictFinalScrollPosition(int velocityX, int velocityY) {
    // ScrollView can *only* scroll for 250ms when using smoothScrollTo and there's
    // no way to customize the scroll duration. So, we create a temporary OverScroller
    // so we can predict where a fling would land and snap to nearby that point.
    OverScroller scroller = new OverScroller(getContext());
    scroller.setFriction(1.0f - decelerationRate);

    // predict where a fling would end up so we can scroll to the nearest snap offset

    int height = Math.round(getContentContainerHeight());
    int width = Math.round(getContentContainerWidth());
    int maximumXOffset = Math.round(getMaxScrollX());
    int maximumYOffset = Math.round(getMaxScrollY());

    scroller.fling(
            (int) startScrollX, // startX
            (int) startScrollY, // startY
            velocityX, // velocityX
            velocityY, // velocityY
            -maximumXOffset, // minX
            0, // maxX
            -maximumYOffset, // minY
            0, // maxY
           width / 2, // overX
           height / 2 // overY
    );

    return scroller;
  }

  private long getAnimationDuration() {
    return animationDuration;
  }

  @Override
  public void setScrollEventThrottle(int i) {

  }

  @Override
  public int getScrollEventThrottle() {
    return 0;
  }

  @Override
  public void setLastScrollDispatchTime(long l) {

  }

  @Override
  public long getLastScrollDispatchTime() {
    return 0;
  }
}
