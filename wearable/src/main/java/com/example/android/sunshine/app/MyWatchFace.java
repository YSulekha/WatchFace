/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }


    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mBackgroundAmbientPaint;
        Paint mBackRectPaint;
        Paint mTextPaint;
        Paint mHighTextPaint;
        Paint mDatePaint;
        boolean mAmbient;
        Calendar mCalendar;

        private Bitmap mBackgroundBitmap;
        private Bitmap mGrayBackgroundBitmap;
        private boolean mBurnInProtection;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float mTextXOffset;
        float mTextYOffset;
        float mYTempOffset;
        float mXLowTempOffset;
        float mYHighTempOffset;
        float mYImageOffset;
        float mYDateOffset;
        float mYLineOffset;
        String highTemp = "1";
        String lowTemp = "0";

        private GoogleApiClient mGoogleApiClient;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            Log.v("OnDataChanged", "OnCreate");
            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = MyWatchFace.this.getResources();


            mXLowTempOffset = resources.getDimension(R.dimen.digital_x_offset_temp);


            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            mBackgroundAmbientPaint = new Paint();
            mBackgroundAmbientPaint.setColor(resources.getColor(R.color.background));

            mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.art_rain);

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mHighTextPaint = new Paint();
            mHighTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));


            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        private void releaseGoogleApiClient() {
            if (mGoogleApiClient != null &&
                    mGoogleApiClient.isConnected()) {
                Wearable.DataApi.removeListener(mGoogleApiClient,
                        this);
                mGoogleApiClient.disconnect();
            }
        }

        @Override
        public void onDestroy() {
            //  mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            /*
             * Create a gray version of the image only if it will look nice on the device in
             * ambient mode. That means we don't want devices that support burn-in
             * protection (slight movements in pixels, not great for images going all the way to
             * edges) and low ambient mode (degrades image quality).
             *
             * Also, if your watch face will know about all images ahead of time (users aren't
             * selecting their own photos for the watch face), it will be more
             * efficient to create a black/white version (png, etc.) and load that when you need it.
             */
            if (!mBurnInProtection && !mLowBitAmbient) {
                initGrayBackgroundBitmap();
            }
            super.onSurfaceChanged(holder, format, width, height);
        }

        private void initGrayBackgroundBitmap() {
            mGrayBackgroundBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                    40, 40, true);
            Canvas canvas = new Canvas(mGrayBackgroundBitmap);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(mGrayBackgroundBitmap, 0, 0, grayPaint);
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                releaseGoogleApiClient();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            //     updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset);

            mYImageOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_image_round : R.dimen.digital_y_offset_image);
            mYDateOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_date_round : R.dimen.digital_y_offset_date);
            mYLineOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_line_round : R.dimen.digital_y_offset_line);
            mYHighTempOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_temp_round : R.dimen.digital_y_offset_temp);

            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float textHighSize = resources.getDimension(R.dimen.digital_text_size_temp);
            float textDateSize = resources.getDimension(R.dimen.digital_text_size_date);

            mTextPaint.setTextSize(textSize);
            mHighTextPaint.setTextSize(textHighSize);
            mDatePaint.setTextSize(textDateSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            Log.v("OnDataChanged", "OnDraw");

            int width = bounds.width();
            int height = bounds.height();

            if (mAmbient) {
                canvas.drawRect(0, 0, width, height, mBackgroundAmbientPaint);
            } else {
                canvas.drawRect(0, 0, width, height, mBackgroundPaint);
            }

            mBackgroundBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                    40, 40, true);


            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK);
            } else if (mAmbient) {
                initGrayBackgroundBitmap();
                canvas.drawBitmap(mGrayBackgroundBitmap, mXOffset, mYImageOffset, mBackgroundAmbientPaint);
            } else {
                //     canvas.drawBitmap(mBackgroundBitmap, mXOffset+20,mYHighTempOffset-30 , mBackgroundPaint);
                canvas.drawBitmap(mBackgroundBitmap, mXOffset, mYImageOffset, mBackgroundPaint);
            }

            // Draw H:MM in both mode
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String time = String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));
            Rect textBounds = new Rect();
            mTextPaint.getTextBounds(time, 0, time.length(), textBounds);
            int textX = Math.abs(bounds.centerX() - textBounds.centerX());
            canvas.drawText(time, textX, mYOffset, mTextPaint);


            String datetext = Utility.getFriendlyDayString(MyWatchFace.this, now, true);
            mDatePaint.getTextBounds(datetext, 0, datetext.length(), textBounds);
            int dateX = Math.abs(bounds.centerX() - textBounds.centerX());
            canvas.drawText(datetext, dateX, mYDateOffset, mDatePaint);
            int lineX = Math.abs(bounds.centerX() - 10);
            canvas.drawLine(lineX, mYLineOffset, lineX+30, mYLineOffset, mTextPaint);

            mHighTextPaint.getTextBounds(highTemp, 0, highTemp.length(), textBounds);
            int highX = Math.abs(bounds.centerX() - textBounds.centerX());
            canvas.drawText(highTemp, highX, mYHighTempOffset, mHighTextPaint);

            canvas.drawText(lowTemp, highX + textBounds.width() + 50, mYHighTempOffset, mHighTextPaint);

        }


        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.v("OnDataChanged", "ddfs");
            String HIGH_TEMP = "com.nanodegree.alse.sunshine.hightemp";
            String LOW_TEMP = "com.nanodegree.alse.sunshine.lowtemp";
            String ICON = "com.nanodegree.alse.sunshine.icodid";
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo("/weatherdata") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        highTemp = dataMap.getString(HIGH_TEMP);
                        lowTemp = dataMap.getString(LOW_TEMP);

                        int icon = dataMap.getInt(ICON);
                        Log.v("HighTemp", highTemp);
                        Log.v("icon", String.valueOf(icon));
                        int iconId = Utility.getArtResourceForWeatherCondition(icon);
                        mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), iconId);
                        Log.v("bitmap", mBackgroundBitmap.toString());
                        this.invalidate();

                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }

        }
    }
}
