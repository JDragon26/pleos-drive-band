package com.team35.driveband.band

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.util.Log
import kotlin.math.abs

/**
 * Pleos Vehicle SDK 이벤트를 "눌림" 으로 바꿔 전달한다. 콜백은 바인더 스레드에서 온다.
 * - 브레이크: 뗌 → 밟음
 * - 깜빡이: 다른 상태 → 왼쪽/오른쪽
 * - 핸들: 가운데(±45°) → 크게 꺾음(±90° 넘음)
 *
 * 비상등·표준 깜빡이 속성(AAOS CAR_EXTERIOR_LIGHTS)은 일반 앱에 권한이 안 나와 못 쓴다 (2026-10-29 확인).
 */
class CarInputs(
    private val onHit: (Pad) -> Unit,
    private val onSteer: (Float) -> Unit,
    private val onParked: (Boolean) -> Unit,
) {
    private lateinit var vehicle: Vehicle
    private val unregister = mutableListOf<() -> Unit>()

    private var brakeDown = false
    private var turn: TurnSignalValue? = null
    private var steerArmed = true // 가운데로 돌아와야 다음 꺾기를 센다

    private fun hit(pad: Pad) {
        Log.i(TAG, "HIT $pad") // PC 쪽 반응 속도 측정 스크립트가 이 줄을 기다린다
        onHit(pad)
    }

    /** 앱이 이미 연결해 둔 Vehicle(CarHub)에 리스너만 단다. initialize·release 는 CarHub 몫 */
    fun start(vehicle: Vehicle) {
        this.vehicle = vehicle
        brakeDown = false; turn = null; steerArmed = true

        val gear = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = onParked(gear == null || gear == VehicleGear.GEAR_PARK)
            override fun onFailed(e: Exception) = warn("기어", e)
        }
        vehicle.getGear().registerGearSelection(gear)
        unregister += { vehicle.getGear().unregisterGearSelection(gear) }

        val brake = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
                val down = pressed == true
                if (down && !brakeDown) hit(Pad.BRAKE)
                brakeDown = down
            }
            override fun onFailed(e: Exception) = warn("브레이크", e)
        }
        vehicle.getBrake().registerBrakePedalStatus(brake)
        unregister += { vehicle.getBrake().unregisterBrakePedalStatus(brake) }

        val signal = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
                if (value != turn) when (value) {
                    TurnSignalValue.LEFT -> hit(Pad.LEFT)
                    TurnSignalValue.RIGHT -> hit(Pad.RIGHT)
                    else -> {}
                }
                turn = value
            }
            override fun onFailed(e: Exception) = warn("깜빡이", e)
        }
        vehicle.getTurnSignal().registerTurnSignalState(signal)
        unregister += { vehicle.getTurnSignal().unregisterTurnSignalState(signal) }

        val steer = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) {
                if (angle == null) return
                onSteer(angle)
                if (steerArmed && abs(angle) > 90) {
                    steerArmed = false
                    hit(Pad.STEER)
                } else if (abs(angle) < 45) {
                    steerArmed = true
                }
            }
            override fun onFailed(e: Exception) = warn("핸들", e)
        }
        vehicle.getSteeringWheel().registerSteeringWheelAngle(steer)
        unregister += { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steer) }
    }

    private fun warn(what: String, t: Throwable) {
        Log.w(TAG, "$what 실패", t)
    }

    fun stop() {
        if (!::vehicle.isInitialized) return
        unregister.forEach { runCatching(it) }
        unregister.clear()
    }

    companion object {
        private const val TAG = "CarBand"
    }
}
