/* Copyright 2019 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

// Modified by Matthias Mueller - Intel Intelligent Systems Lab - 2020

package org.openbot.tracking;

import static java.lang.Math.abs;
import static java.lang.Math.sqrt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import org.openbot.env.BorderedText;
import org.openbot.env.ImageUtils;
import org.openbot.env.Logger;
import org.openbot.tflite.Detector.Recognition;
import org.openbot.vehicle.Control;

/** A tracker that handles non-max suppression and matches existing objects to new detections. */
public class MultiBoxTracker {
  private static final float TEXT_SIZE_DIP = 18;
  private static final float MIN_SIZE = 16.0f;
  private static final int[] COLORS = {
    Color.BLUE,
    Color.RED,
    Color.GREEN,
    Color.YELLOW,
    Color.CYAN,
    Color.MAGENTA,
    Color.WHITE,
    Color.parseColor("#55FF55"),
    Color.parseColor("#FFA500"),
    Color.parseColor("#FF8888"),
    Color.parseColor("#AAAAFF"),
    Color.parseColor("#FFFFAA"),
    Color.parseColor("#55AAAA"),
    Color.parseColor("#AA33AA"),
    Color.parseColor("#0D0068")
  };
  final List<Pair<Float, RectF>> screenRects = new LinkedList<Pair<Float, RectF>>();
  private final Logger logger = new Logger();
  private final Queue<Integer> availableColors = new LinkedList<Integer>();
  private final List<TrackedRecognition> trackedObjects = new LinkedList<TrackedRecognition>();
  private final Paint boxPaint = new Paint();
  private final float textSizePx;
  private final BorderedText borderedText;
  private Matrix frameToCanvasMatrix;
  private int frameWidth;
  private int frameHeight;
  private int sensorOrientation;
  private float leftControl;
  private float rightControl;
  private float servoAngle = 0;
  private boolean useDynamicSpeed = false;
  private int trackId = -1;
  private ByteTracker byteTracker = new ByteTracker();

  private float speedEma = 0.0F;

  private RobotController robot = new RobotController();

  private Control lastControl = null;

  public MultiBoxTracker(final Context context) {
    byteTracker.init(10, 50); // TODO wuyijun 待优化

    for (final int color : COLORS) {
      availableColors.add(color);
    }

    boxPaint.setColor(Color.RED);
    boxPaint.setStyle(Style.STROKE);
    boxPaint.setStrokeWidth(10.0f);
    boxPaint.setStrokeCap(Cap.ROUND);
    boxPaint.setStrokeJoin(Join.ROUND);
    boxPaint.setStrokeMiter(100);

    textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
  }

  public synchronized void setFrameConfiguration(
      final int width, final int height, final int sensorOrientation) {
    frameWidth = width;
    frameHeight = height;
    this.sensorOrientation = sensorOrientation;
  }

  public synchronized void drawDebug(final Canvas canvas) {
    final Paint textPaint = new Paint();
    textPaint.setColor(Color.WHITE);
    textPaint.setTextSize(60.0f);

    final Paint boxPaint = new Paint();
    boxPaint.setColor(Color.RED);
    boxPaint.setAlpha(200);
    boxPaint.setStyle(Style.STROKE);

    for (final Pair<Float, RectF> detection : screenRects) {
      final RectF rect = detection.second;
      canvas.drawRect(rect, boxPaint);
      canvas.drawText("" + detection.first, rect.left, rect.top, textPaint);
      borderedText.drawText(canvas, rect.centerX(), rect.centerY(), "" + detection.first);
    }
  }

  public synchronized void trackResults(final List<Recognition> results, final long timestamp, final boolean byteTrack) {
    logger.i("Processing %d results from %d", results.size(), timestamp);
    if (byteTrack) {
      trackResults(results, timestamp);
    } else {
      processResults(results);
    }
  }

  public synchronized void trackResults(final List<Recognition> results, final long timestamp) {

    ArrayList<JavaObject> resultList = new ArrayList<>();
    for (int i = 0; i < results.size(); i ++) {
      Recognition recognition = results.get(i);
      JavaObject detected = new JavaObject(recognition.getLocation(), recognition.getClassId(), recognition.getConfidence());
      resultList.add(detected);
    }
    List<JavaSTrack> stracks = byteTracker.update(resultList);
    int index = -1;
    for (int i = 0; i < stracks.size(); i ++) {
      JavaSTrack strack = stracks.get(i);
      if (strack.trackId == this.trackId || this.trackId == -1) {
        index = i;
        break;
      }
    }
//    int recIndex = -1; // TODO wuyijun 根据RecF的值匹配recognition index
//    for (int i = 0; i < results.size() && index >= 0; i ++) {
//      if (results.get(i).getLocation().equals(stracks.get(index).rect)) {
//        recIndex = i;
//        break;
//      }
//    }
//
//    // swap i and 0
//    if (recIndex > 0) {
//      Recognition temp = results.get(0);
//      results.set(0, results.get(recIndex));
//      results.set(recIndex, temp);
//    }

    List<Recognition> trackedRecognitions = strackToRecognition(stracks, index);

    if (this.trackId == -1 && !results.isEmpty() && index >= 0 && index < stracks.size()) {
      this.trackId = stracks.get(index).trackId;
    } else if (index == -1 && !results.isEmpty() && !stracks.isEmpty()) {
      this.trackId = stracks.get(0).trackId;
    }
    if (trackedRecognitions.isEmpty()) {
      //trackedRecognitions = strackToRecognition(byteTracker.predict_lost(), 0);
    }
    processResults(trackedRecognitions);
    //processResults(results);
  }

  @NonNull
  private static List<Recognition> strackToRecognition(List<JavaSTrack> stracks, int index) {
    List<Recognition> trackedRecognitions = new ArrayList<>();
    if (index >= 0 && index < stracks.size()) {
      Recognition recognition = new Recognition("0", "" + stracks.get(index).trackId, stracks.get(index).score, stracks.get(index).rect, 1);
      trackedRecognitions.add(recognition);
    }
    for (int i = 0; i < stracks.size(); i ++) {
      if (i != index) {
        Recognition recognition = new Recognition("0", "" + stracks.get(i).trackId, stracks.get(i).score, stracks.get(i).rect, 1);
        trackedRecognitions.add(recognition);
      }
    }
    return trackedRecognitions;
  }

  private Matrix getFrameToCanvasMatrix() {
    return frameToCanvasMatrix;
  }

  private void updateFrameToCanvasMatrix(int canvasHeight, int canvasWidth) {
    final boolean rotated = sensorOrientation % 180 == 90;
    final float multiplier =
        Math.min(
            canvasHeight / (float) (rotated ? frameWidth : frameHeight),
            canvasWidth / (float) (rotated ? frameHeight : frameWidth));
    frameToCanvasMatrix =
        ImageUtils.getTransformationMatrix(
            frameWidth,
            frameHeight,
            (int) (multiplier * (rotated ? frameHeight : frameWidth)),
            (int) (multiplier * (rotated ? frameWidth : frameHeight)),
            sensorOrientation,
            new RectF(0, 0, 0, 0),
            false);
  }

  public synchronized Control updateTarget() {
    return updateTarget(false);
  }

  public synchronized Control updateTarget(boolean fastTurn) {
    return updateTarget2(false, 0.0f);
  }

  /**
   * Determine the robot controls/steering from the position of the tracked object/person on screen.
   * The follow speed is adjusted based on the area of the bounding box of the tracked object.
   * Assumption: large object box --> close to object --> slow down
   *
   * @return the adjusted speed control for left and right wheels in the range -1.0 ... 1.0
   */
  public synchronized Control updateTarget(boolean fastTurn, float servoAngle) {
    if (!trackedObjects.isEmpty()) {
      // Pick detection with highest probability
      final RectF trackedPos = new RectF(trackedObjects.get(0).location);
      final boolean rotated = sensorOrientation % 180 == 90;
      float imgWidth = (float) (rotated ? frameHeight : frameWidth);
      // calculate track box area for distance estimate
      float boxArea = trackedPos.height() * trackedPos.width();
      float centerX = (rotated ? trackedPos.centerY() : trackedPos.centerX());
      float leftX = (rotated ? trackedPos.top : trackedPos.left);
      float rightX = (rotated ? trackedPos.bottom : trackedPos.right);
      // Make sure object center is in frame
      centerX = Math.max(0.0f, Math.min(centerX, imgWidth));
      // Scale relative position along x-axis between -1 and 1
      float fovDegree = 50.0f;
      float temp = 1.0f - 2.0f * (centerX / imgWidth);
      float x_pos_norm = (temp + servoAngle * 180/fovDegree) / (1 + 180/fovDegree);
      float angleAdjustSpeed = 0.06f;
      float servoAngleChange = angleAdjustSpeed * ( temp);
      this.servoAngle = servoAngle + servoAngleChange;
      this.servoAngle = Math.max(-1.0f, Math.min(this.servoAngle, 1.0f));
      // Make sure object center is in frame
      leftX = Math.max(0.0f, Math.min(leftX, imgWidth));
      // Scale relative position along x-axis between -1 and 1
      float left_norm = 1.0f - 2.0f * leftX / imgWidth;
      // Make sure object center is in frame
      rightX = Math.max(0.0f, Math.min(rightX, imgWidth));
      // Scale relative position along x-axis between -1 and 1
      float right_norm = 1.0f - 2.0f * rightX / imgWidth;
      // Scale for steering signal and account for rotation,
      float x_pos_scaled = rotated ? -x_pos_norm * 1.0f : x_pos_norm * 1.0f;
      //// Scale by "exponential" function: y = x / sqrt(1-x^2)
      // Math.max (Math.min(x_pos_norm / Math.sqrt(1 - x_pos_norm * x_pos_norm),2),-2);
      boolean goingOutOfFOV = false;
      if (abs(x_pos_norm) > 0.6 && left_norm * right_norm > 0 && (abs(left_norm) < 0.05 || abs(left_norm) > 0.95 || abs(right_norm) < 0.05 || abs(right_norm) > 0.95)) {
        goingOutOfFOV = true;
      }

      if (x_pos_scaled < 0) {
        leftControl = 1.0f;
        rightControl = 1.0f + x_pos_scaled;
      } else {
        leftControl = 1.0f - x_pos_scaled;
        rightControl = 1.0f;
      }

      // adjust speed depending on size of detected object bounding box
      if (useDynamicSpeed) {
        float scaleFactor = 1.0f - boxArea / (frameWidth * frameHeight);
        float speedFactor = scaleFactor > 0.75f ? 1.0f : (scaleFactor + 0.25f); // tracked object far, full speed
        // apply scale factor if tracked object is not too near, otherwise stop
        if (fastTurn && false) {
          leftControl *= speedFactor * 1.05;
          rightControl *= speedFactor * 1.05;

          float mean = (leftControl + rightControl) / 2.0F;
          float diff = (leftControl - rightControl) / 2.0F;
          float frictionFactor = 1.0F; // 户外粗糙水泥地面1.0，室内地板0.8，室内光滑地砖0.6
          float alpha = 0.1F;
          /**
           * 指数移动平均计算器。
           *         :param alpha: 平滑常数 (0 < alpha <= 1)。
           *                       alpha越小，历史数据权重越大，曲线越平滑。
           *                       alpha越大，近期数据权重越大，曲线越敏感。
           *                       常用的alpha计算方式是 2 / (N + 1)，N为周期。
           *                       例如，N=19时，alpha=0.1。
           */
          float turnSensitivity = (goingOutOfFOV ? 1.0F : 0.7F) * frictionFactor;
          if ((speedEma < 0.01 && goingOutOfFOV) || scaleFactor < 0.25f) { //停止状态下被引导转向，或已足够接近
            leftControl = 0.0F + diff / scaleFactor * turnSensitivity;
            rightControl = 0.0F - diff / scaleFactor * turnSensitivity;
            speedEma = speedEma * (1 - alpha) + 0 * alpha;
          } else  {
            leftControl = mean + diff / scaleFactor * turnSensitivity;
            rightControl = mean - diff / scaleFactor * turnSensitivity;
            speedEma = speedEma * (1 - alpha) + mean * alpha;
          }
        } else {
          if (scaleFactor > 0.15f) {
            leftControl *= scaleFactor;
            rightControl *= scaleFactor;
          } else {
            leftControl = 0.0f;
            rightControl = 0.0f;
          }
        }
      }
    } else {
      leftControl = 0.0f;
      rightControl = 0.0f;
    }

    return new Control(
        (0 > sensorOrientation) ? rightControl : leftControl,
        (0 > sensorOrientation) ? leftControl : rightControl,
            this.servoAngle);
  }

  public synchronized Control updateTarget2(boolean fastTurn, float servoAngle) {
    if (!trackedObjects.isEmpty()) {
      // Pick detection with highest probability
      final RectF trackedPos = new RectF(trackedObjects.get(0).location);
      final boolean rotated = sensorOrientation % 180 == 90;
      float imgWidth = (float) (rotated ? frameHeight : frameWidth);
      // calculate track box area for distance estimate
      float boxArea = trackedPos.height() * trackedPos.width();
      float percent = boxArea / (frameWidth * frameHeight);
      float fovVert = 70.0f;
      float distanceEst = (float) (1.8 * (sqrt(3.0) / 2.0) / (percent / 0.5f) / (fovVert / 60.0f)); // 1.8 meter high, 60 fov, 0.5 percent -> distance =
      float centerX = (rotated ? trackedPos.centerY() : trackedPos.centerX());
      float leftX = (rotated ? trackedPos.top : trackedPos.left);
      float rightX = (rotated ? trackedPos.bottom : trackedPos.right);
      // Make sure object center is in frame
      centerX = Math.max(0.0f, Math.min(centerX, imgWidth));
      // Scale relative position along x-axis between -1 and 1
      float fovHoriz = 50.0f;
      float x_pos_norm_raw = 1.0f - 2.0f * (centerX / imgWidth);
      float directionEstWithinImg = (float) (Math.atan(x_pos_norm_raw) / (Math.PI / 4) * fovHoriz / 2 / 180);
      float directionEst = directionEstWithinImg + servoAngle;
      float x_pos_norm = (x_pos_norm_raw + servoAngle * 180 / fovHoriz) / (1 + 180 / fovHoriz);
      //float angleAdjustSpeed = 0.06f;
      //float servoAngleChange = angleAdjustSpeed * (x_pos_norm_raw);
      //this.servoAngle = servoAngle + servoAngleChange;
      //this.servoAngle = Math.max(-1.0f, Math.min(this.servoAngle, 1.0f));

      // System.out.println("PID机器人控制器启动，按 Ctrl+C 停止。");

      TargetInfo targetInfo = new TargetInfo(distanceEst, directionEst);
      Control control = robot.update(targetInfo, lastControl);
      if (Math.abs(control.getServoAngle()) > 1) {
        control = new Control(control.getLeft(), control.getRight(), control.getServoAngle() > 0 ? 1 : -1);
      }
      lastControl = control;

      // 保持稳定的控制频率，例如100ms -> 10Hz
      // Thread.sleep(100);

      if (control != null) {
        leftControl = control.getLeft();
        rightControl = control.getRight();
        this.servoAngle = control.getServoAngle();
      }
      return new Control(
              (0 > sensorOrientation) ? rightControl : leftControl,
              (0 > sensorOrientation) ? leftControl : rightControl,
              this.servoAngle);
    } else {
      return new Control(0, 0, this.servoAngle);
    }
  }

  public synchronized void draw(final Canvas canvas) {
    updateFrameToCanvasMatrix(canvas.getHeight(), canvas.getWidth());

    for (final TrackedRecognition recognition : trackedObjects) {
      final RectF trackedPos = new RectF(recognition.location);

      getFrameToCanvasMatrix().mapRect(trackedPos);
      boxPaint.setColor(recognition.color);

      float cornerSize = Math.min(trackedPos.width(), trackedPos.height()) / 8.0f;
      canvas.drawRoundRect(trackedPos, cornerSize, cornerSize, boxPaint);

      final String labelString =
          !TextUtils.isEmpty(recognition.title)
              ? String.format(
                  Locale.US, "%s %.2f", recognition.title, (100 * recognition.detectionConfidence))
              : String.format(Locale.US, "%.2f", 100 * recognition.detectionConfidence);
      borderedText.drawText(
          canvas, trackedPos.left + cornerSize, trackedPos.top, labelString + "%", boxPaint);

      //      if (recognition == trackedObjects.get(0)) {
      //        borderedText.drawText(
      //                canvas,
      //                trackedPos.left + cornerSize,
      //                trackedPos.top + 40.0f,
      //                String.format(Locale.US, "%.2f", leftControl) + "," + String.format("%.2f",
      // rightControl),
      //                boxPaint);
      //      }
    }
  }

  public void clearTrackedObjects() {
    trackedObjects.clear();
  }

  private void processResults(final List<Recognition> results) {
    final List<Pair<Float, Recognition>> rectsToTrack = new LinkedList<Pair<Float, Recognition>>();

    screenRects.clear();
    final Matrix rgbFrameToScreen = new Matrix(getFrameToCanvasMatrix());

    for (final Recognition result : results) {
      if (result.getLocation() == null) {
        continue;
      }
      final RectF detectionFrameRect = new RectF(result.getLocation());

      final RectF detectionScreenRect = new RectF();
      rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect);

      logger.v(
          "Result! Frame: " + result.getLocation() + " mapped to screen:" + detectionScreenRect);

      screenRects.add(new Pair<Float, RectF>(result.getConfidence(), detectionScreenRect));

      if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
        logger.w("Degenerate rectangle! " + detectionFrameRect);
        continue;
      }

      rectsToTrack.add(new Pair<Float, Recognition>(result.getConfidence(), result));
    }

    // Clear so objects don't stay if nothing detected.
    trackedObjects.clear();

    if (rectsToTrack.isEmpty()) {
      logger.v("Nothing to track, aborting.");
      return;
    }

    // trackedObjects.clear();
    for (final Pair<Float, Recognition> potential : rectsToTrack) {
      final TrackedRecognition trackedRecognition = new TrackedRecognition();
      trackedRecognition.detectionConfidence = potential.first;
      trackedRecognition.location = new RectF(potential.second.getLocation());
      trackedRecognition.title = potential.second.getTitle();
      trackedRecognition.color = COLORS[trackedObjects.size()];
      trackedObjects.add(trackedRecognition);

      if (trackedObjects.size() >= COLORS.length) {
        break;
      }
    }
  }

  /**
   * Set use of dynamic speed on or off (used in updateTarget())
   *
   * @param isEnabled
   */
  public void setDynamicSpeed(boolean isEnabled) {
    useDynamicSpeed = isEnabled;
  }

  private static class TrackedRecognition {
    RectF location;
    float detectionConfidence;
    int color;
    String title;
  }
}
