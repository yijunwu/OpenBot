package org.openbot.vehicle;

public class Control {
  private final float left;
  private final float right;
  private final float servoAngle;

  public Control(float left, float right) {
    this(left, right, 0.0f);
  }

  public Control(float left, float right, float servoAngle) {
    this.left = Math.max(-1.f, Math.min(1.f, left));
    this.right = Math.max(-1.f, Math.min(1.f, right));
    this.servoAngle = servoAngle;
  }

  public float getLeft() {
    return left;
  }

  public float getRight() {
    return right;
  }

  public float getServoAngle() {
    return servoAngle;
  }

  public Control mirror() {
    return new Control(this.right, this.left, - this.servoAngle);
  }
}
