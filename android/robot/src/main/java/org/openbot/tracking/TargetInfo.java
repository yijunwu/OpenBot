package org.openbot.tracking;

/**
 * 用于封装目标的信息。
 */
public final class TargetInfo {
    double distance;
    double angle;

    /**
     * @param distance 距离目标的距离 (单位：米)
     * @param angle    距离目标的方向角 (单位：弧度)
     */
    public TargetInfo(double distance, double angle) {
        this.distance = distance;
        this.angle = angle;
    }

    public double getDistance() {
        return distance;
    }

    public double getAngle() {
        return angle;
    }
}