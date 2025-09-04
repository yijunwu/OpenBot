package org.openbot.tracking;

import static java.lang.Math.PI;
import static java.lang.Math.abs;
import static java.lang.Math.signum;

import org.openbot.vehicle.Control;

public class PIDController {

    // --- 物理参数 ---
    private final double wheelBase;
    private final double maxSpeed;

    // --- 目标参数 ---
    private final double targetDistance;

    // --- PID 控制器实例 ---
    private final PIDCalculator distanceCalculator;
    private final PIDCalculator angleCalculator;
    private final PIDCalculator servoAngleCalculator;

    // --- 时间跟踪 ---
    private long lastUpdateTimeNanos;

    /**
     * 构造函数
     */
    public PIDController(double wheelBase, double maxSpeed, double targetDistance,
                              double kp_d, double ki_d, double kd_d, // 距离PID增益
                              double kp_a, double ki_a, double kd_a, // 角度PID增益
                              double kp_sa, double ki_sa, double kd_sa) { // 舵机角度PID增益
        this.wheelBase = wheelBase;
        this.maxSpeed = maxSpeed;
        this.targetDistance = targetDistance;

        // 初始化两个独立的PID控制器
        this.distanceCalculator = new PIDCalculator(kp_d, ki_d, kd_d);
        this.angleCalculator = new PIDCalculator(kp_a, ki_a, kd_a);
        this.servoAngleCalculator = new PIDCalculator(kp_sa, ki_sa, kd_sa);

        // 初始化时间戳
        this.lastUpdateTimeNanos = System.nanoTime();
    }

    /**
     * 执行单次控制循环的更新
     */
    public Control update(TargetInfo target, Control lastControl) {
        // 1. 计算时间差 (dt)，单位为秒
        long currentTimeNanos = System.nanoTime();
        double dt = (currentTimeNanos - lastUpdateTimeNanos) / 1_000_000_000.0;

        // 如果dt为0或过大（例如首次运行或暂停后），则跳过本次计算防止异常
        if (dt <= 0.001 || dt > 0.5) {
            dt = 0;
        }
        lastUpdateTimeNanos = currentTimeNanos;

        // 2. 获取传感器数据
        //TargetInfo target = sensor.getTargetInfo();

        // 3. 计算误差
        double errorDistance = target.getDistance() - this.targetDistance;
        double errorAngle = target.getAngle();
        double servoAngle = lastControl == null ? 0 : lastControl.getServoAngle();
        double errorServoAngle = target.getAngle() - servoAngle;

        // 4. 使用PID控制器计算期望的线速度和角速度
        double v = distanceCalculator.calculate(errorDistance, dt);
        double omega = angleCalculator.calculate(errorAngle, dt);
        double omegaServo = servoAngleCalculator.calculate(errorServoAngle, dt) - omega * 0.013; //0.001 ~ 0.1

        // 5. 逆向运动学解算 -> 得到左右轮速度
        double v_R = v + (omega * this.wheelBase) / 2.0;
        double v_L = v - (omega * this.wheelBase) / 2.0;

        // 6. 速度限制
//        v_R = Math.max(-this.maxSpeed, Math.min(v_R, this.maxSpeed));
//        v_L = Math.max(-this.maxSpeed, Math.min(v_L, this.maxSpeed));
        if (v_R > this.maxSpeed && v_L > 0) {
            v_R = this.maxSpeed;
            v_L = v_R - (omega * this.wheelBase);
        }
        if (v_L > this.maxSpeed && v_R > 0) {
            v_L = this.maxSpeed;
            v_R = v_L + (omega * this.wheelBase);
        }
        if (abs(omegaServo) > (2.0 / 180.0 * PI / 0.1) * 1.5 * dt) {
            omegaServo = signum(omegaServo) * 2.0 / 180.0 * PI / 0.1 * 1.5 * dt;
        }

        boolean b = !Double.isFinite(servoAngle) || !Double.isFinite(omegaServo) || !Float.isFinite((float) (servoAngle + omegaServo));

        // 7. 返回结果
        return new Control((float)v_L, (float)v_R, (float)(servoAngle + omegaServo));
    }
}