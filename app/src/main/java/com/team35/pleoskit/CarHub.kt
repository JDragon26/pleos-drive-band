package com.team35.pleoskit

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryLevelListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureValueListener
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** 좌석(=문·창문 위치도 같은 이름). 국내 왼쪽 핸들 기준 운전석 = 1열 왼쪽 */
enum class Seat(val label: String) { DRIVER("운전석"), FRONT("조수석"), REAR_LEFT("뒷좌석 왼쪽"), REAR_RIGHT("뒷좌석 오른쪽") }

enum class Wheel(val label: String) { FL("앞 왼쪽"), FR("앞 오른쪽"), RL("뒤 왼쪽"), RR("뒤 오른쪽") }

/**
 * 차 상태 한 장. 모르는 값은 null. CarHub 가 메인 스레드에서 새 사본으로 바꿔 끼운다.
 * 시각(ms)은 SystemClock.elapsedRealtime 기준.
 */
data class CarState(
    val atMs: Long = 0,
    val gear: VehicleGear? = null,
    val speedKph: Double? = null,
    val brake: Boolean = false,
    val steering: Float = 0f,
    val turn: TurnSignalValue? = null,
    val cruise: Boolean = false,
    val odometerKm: Double? = null,
    val occupied: Map<Seat, Boolean> = emptyMap(),
    val belted: Map<Seat, Boolean> = emptyMap(),
    val backrest: Map<Seat, Int> = emptyMap(),     // 등받이 위치 (0~100, 클수록 젖힘 — 에뮬레이터 기준)
    val doorOpen: Map<Seat, Boolean> = emptyMap(),
    val trunkOpen: Boolean = false,
    val locked: Boolean? = null,
    val window: Map<Seat, Int> = emptyMap(),        // 창문 열림 (에뮬레이터 0~10: Virtual Console 40% → 4)
    val wiper: Boolean = false,
    val hvacOn: Boolean? = null,
    val recirc: Boolean? = null,
    val fan: Int? = null,
    val cabinTempC: Float? = null,
    val batteryKwh: Double? = null,
    val soc: Int? = null,
    val tireKpa: Map<Wheel, Float> = emptyMap(),
) {
    /** P 가 아니면 주행 중으로 본다. 기어를 모르면 정차로 본다 (전체 화면) */
    val parked get() = gear == null || gear == VehicleGear.GEAR_PARK
    val riders get() = occupied.count { it.value }
    val anyWindowOpen get() = window.values.any { it > 0 }
}

/** 구독할 분야. 앱마다 쓰는 것만 골라야 마켓 권한 심사에 맞는다. */
enum class Signal { DRIVE, SPEED, SEATS, DOORS, WINDOWS, CLIMATE, ENERGY, TIRES }

/**
 * Pleos Vehicle SDK + AAOS 표준 차속을 한 곳에서 받아 CarState 로 모은다.
 * onChange(이전, 지금) 은 메인 스레드에서 불린다.
 *
 * 알려진 문제 보정:
 * - Vehicle SDK 2.3.2 좌석 탑승 값이 반대로 온다 (SDK 가 1 을 true 로, AAOS 표준은 1 = 빈자리)
 * - EV_BATTERY_LEVEL 단위는 Wh
 */
class CarHub(
    context: Context,
    private val signals: Set<Signal>,
    private val onChange: (CarState, CarState) -> Unit,
) {
    private val appContext = context.applicationContext
    /** 같은 앱의 다른 기능도 이 연결에 리스너만 더 단다 — Vehicle 을 하나 더 만들어 release 하면 이쪽 구독까지 끊긴다 */
    val vehicle = Vehicle(appContext)
    private val main = Handler(Looper.getMainLooper())
    private val unregister = mutableListOf<() -> Unit>()
    private var car: Car? = null

    var state = CarState()
        private set

    private fun set(f: (CarState) -> CarState) {
        main.post {
            val old = state
            val new = f(old).copy(atMs = SystemClock.elapsedRealtime())
            if (new != old.copy(atMs = new.atMs)) {
                state = new
                onChange(old, new)
            }
        }
    }

    /** 구독 하나. 권한·차종 때문에 실패해도 나머지 구독은 계속한다 */
    private inline fun reg(what: String, block: () -> Unit) {
        runCatching(block).onFailure { warn(what, it) }
    }

    private fun warn(what: String, e: Throwable) {
        Log.i(TAG, "$what 없음/실패: ${e.message}")
    }

    fun start() {
        runCatching { vehicle.initialize() }.onFailure { warn("Vehicle", it) }
        for (s in signals) runCatching {
            when (s) {
                Signal.DRIVE -> drive()
                Signal.SPEED -> speed()
                Signal.SEATS -> seats()
                Signal.DOORS -> doors()
                Signal.WINDOWS -> windows()
                Signal.CLIMATE -> climate()
                Signal.ENERGY -> energy()
                Signal.TIRES -> tires()
            }
        }.onFailure { warn(s.name, it) }
    }

    fun stop() {
        unregister.forEach { runCatching(it) }
        unregister.clear()
        runCatching { vehicle.release() }
        runCatching { car?.disconnect() }
    }

    private fun drive() {
        val gear = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) = set { it.copy(gear = gear) }
            override fun onFailed(e: Exception) = warn("기어", e)
        }
        reg("registerGearSelection") {
            vehicle.getGear().registerGearSelection(gear)
            unregister += { vehicle.getGear().unregisterGearSelection(gear) }
        }

        val brake = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) = set { it.copy(brake = pressed == true) }
            override fun onFailed(e: Exception) = warn("브레이크", e)
        }
        reg("registerBrakePedalStatus") {
            vehicle.getBrake().registerBrakePedalStatus(brake)
            unregister += { vehicle.getBrake().unregisterBrakePedalStatus(brake) }
        }

        val steer = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) { if (angle != null) set { it.copy(steering = angle) } }
            override fun onFailed(e: Exception) = warn("핸들", e)
        }
        reg("registerSteeringWheelAngle") {
            vehicle.getSteeringWheel().registerSteeringWheelAngle(steer)
            unregister += { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steer) }
        }

        val turn = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = set { it.copy(turn = value) }
            override fun onFailed(e: Exception) = warn("깜빡이", e)
        }
        reg("registerTurnSignalState") {
            vehicle.getTurnSignal().registerTurnSignalState(turn)
            unregister += { vehicle.getTurnSignal().unregisterTurnSignalState(turn) }
        }

        val cruise = object : CruiseControlStateListener {
            override fun onCruiseControlStateUpdated(state: CruiseControl?) {
                // Virtual Console 에선 'full_autonomous' 만 ACTIVATED 로 들어온다 (longitudinal·lateral 은 전달 안 됨)
                set { it.copy(cruise = state == CruiseControl.ACTIVATED) }
            }
            override fun onFailed(e: Exception) = warn("크루즈", e)
        }
        reg("registerCruiseControlState") {
            vehicle.getDrivingMode().registerCruiseControlState(cruise)
            unregister += { vehicle.getDrivingMode().unregisterCruiseControlState(cruise) }
        }

        val odo = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(distance: Float?) { if (distance != null) set { it.copy(odometerKm = distance.toDouble()) } }
            override fun onFailed(e: Exception) = warn("주행거리", e)
        }
        reg("registerDistanceDriven") {
            vehicle.getOdometer().registerDistanceDriven(odo)
            unregister += { vehicle.getOdometer().unregisterDistanceDriven(odo) }
        }
    }

    /** 차속: Pleos SDK 에 없어 AAOS 표준 PERF_VEHICLE_SPEED(m/s) */
    private fun speed() {
        val c = Car.createCar(appContext).also { car = it }
        val props = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) {
                val mps = v.value as? Float ?: return
                set { it.copy(speedKph = mps * 3.6) }
            }
            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                Log.i(TAG, "차속 오류")
            }
        }
        reg("registerCallback") {
            props.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            unregister += { props.unregisterCallback(cb) }
        }
    }

    private val seatValues = mapOf(
        Seat.DRIVER to SeatValue.ROW_1_LEFT, Seat.FRONT to SeatValue.ROW_1_RIGHT,
        Seat.REAR_LEFT to SeatValue.ROW_2_LEFT, Seat.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
    )

    private fun seats() {
        val api = vehicle.getSeat()
        for ((seat, v) in seatValues) {
            val area = SeatArea(v)
            val occ = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupied: Boolean?) {
                    if (occupied != null) set { it.copy(occupied = it.occupied + (seat to (occupied != SEAT_SDK_INVERTED))) }
                }
                override fun onFailed(e: Exception) = warn("탑승 ${seat.label}", e)
            }
            reg("registerSeatOccupancy") {
                api.registerSeatOccupancy(area, occ)
                unregister += { api.unregisterSeatOccupancy(area, occ) }
            }

            val belt = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) {
                    if (buckled != null) set { it.copy(belted = it.belted + (seat to buckled)) }
                }
                override fun onFailed(e: Exception) = warn("벨트 ${seat.label}", e)
            }
            runCatching {
                reg("registerSeatBeltBuckledState") {
                    api.registerSeatBeltBuckledState(area, belt)
                    unregister += { api.unregisterSeatBeltBuckledState(area, belt) }
                }
            }

            val back = object : SeatBackrestAnglePositionListener {
                override fun onSeatBackrestAnglePositionUpdated(position: Int?) {
                    if (position != null) set { it.copy(backrest = it.backrest + (seat to position)) }
                }
                override fun onFailed(e: Exception) = warn("등받이 ${seat.label}", e)
            }
            runCatching {
                reg("registerSeatBackrestAnglePosition") {
                    api.registerSeatBackrestAnglePosition(area, back)
                    unregister += { api.unregisterSeatBackrestAnglePosition(area, back) }
                }
            }
        }
    }

    private fun doors() {
        val api = vehicle.getDoor()
        val doors = mapOf(
            Seat.DRIVER to DoorValue.ROW_1_LEFT, Seat.FRONT to DoorValue.ROW_1_RIGHT,
            Seat.REAR_LEFT to DoorValue.ROW_2_LEFT, Seat.REAR_RIGHT to DoorValue.ROW_2_RIGHT,
        )
        for ((seat, v) in doors) {
            val area = DoorArea(v)
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(position: Int?) {
                    if (position != null) set { it.copy(doorOpen = it.doorOpen + (seat to (position > 0))) }
                }
                override fun onFailed(e: Exception) = warn("문 ${seat.label}", e)
            }
            reg("registerDoorPosition") {
                api.registerDoorPosition(area, l)
                unregister += { api.unregisterDoorPosition(area, l) }
            }
        }
        val trunkArea = DoorArea(DoorValue.REAR)
        val trunk = object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) { if (position != null) set { it.copy(trunkOpen = position > 0) } }
            override fun onFailed(e: Exception) = warn("트렁크", e)
        }
        runCatching {
            reg("registerDoorPosition") {
                api.registerDoorPosition(trunkArea, trunk)
                unregister += { api.unregisterDoorPosition(trunkArea, trunk) }
            }
        }
        // 잠금은 문 4개 전체 영역(areaId 85)으로 온다 (에뮬레이터 확인)
        val lockArea = DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
        val lock = object : DoorLockStateListener {
            override fun onDoorLockStateUpdated(locked: Boolean?) = set { it.copy(locked = locked) }
            override fun onFailed(e: Exception) = warn("잠금", e)
        }
        reg("registerDoorLockState") {
            api.registerDoorLockState(lockArea, lock)
            unregister += { api.unregisterDoorLockState(lockArea, lock) }
        }
    }

    private fun windows() {
        val api = vehicle.getWindow()
        val wins = mapOf(
            Seat.DRIVER to WindowValue.ROW_1_LEFT, Seat.FRONT to WindowValue.ROW_1_RIGHT,
            Seat.REAR_LEFT to WindowValue.ROW_2_LEFT, Seat.REAR_RIGHT to WindowValue.ROW_2_RIGHT,
        )
        for ((seat, v) in wins) {
            val area = WindowArea(v)
            val l = object : WindowPositionListener {
                override fun onWindowPositionUpdated(position: Int?) {
                    if (position != null) set { it.copy(window = it.window + (seat to position)) }
                }
                override fun onFailed(e: Exception) = warn("창문 ${seat.label}", e)
            }
            reg("registerWindowPosition") {
                api.registerWindowPosition(area, l)
                unregister += { api.unregisterWindowPosition(area, l) }
            }
        }
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiper = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = set { it.copy(wiper = state == WiperState.ON) }
            override fun onFailed(e: Exception) = warn("와이퍼", e)
        }
        reg("registerWiperState") {
            vehicle.getWiper().registerWiperState(front, wiper)
            unregister += { vehicle.getWiper().unregisterWiperState(front, wiper) }
        }
    }

    private fun climate() {
        val api = vehicle.getHVAC()
        // 에뮬레이터 확인: 전원은 1·2열 전체(areaId 117), 풍량은 1열(5), 실내 온도는 운전석(1)
        val all = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        val area = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT)
        val driverArea = SeatArea(SeatValue.ROW_1_LEFT)
        val power = object : HvacPowerStateListener {
            override fun onHvacPowerStateUpdated(on: Boolean?) = set { it.copy(hvacOn = on) }
            override fun onFailed(e: Exception) = warn("공조 전원", e)
        }
        reg("registerHvacPowerState") {
            api.registerHvacPowerState(all, power)
            unregister += { api.unregisterHvacPowerState(all, power) }
        }

        val recirc = object : HvacRecirculationStateListener {
            override fun onHvacAutoRecirculationStateUpdated(on: Boolean?) = set { it.copy(recirc = on) }
            override fun onFailed(e: Exception) = warn("내기순환", e)
        }
        reg("registerHvacRecirculationState") {
            api.registerHvacRecirculationState(all, recirc)
            unregister += { api.unregisterHvacRecirculationState(all, recirc) }
        }

        val fan = object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(speed: Int?) = set { it.copy(fan = speed) }
            override fun onFailed(e: Exception) = warn("풍량", e)
        }
        reg("registerHvacFanSpeed") {
            api.registerHvacFanSpeed(area, fan)
            unregister += { api.unregisterHvacFanSpeed(area, fan) }
        }

        val temp = object : HvacTemperatureValueListener {
            override fun onHvacTemperatureUpdated(celsius: Float?, fahrenheit: Float?) {
                if (celsius != null) set { it.copy(cabinTempC = celsius) }
            }
            override fun onFailed(e: Exception) = warn("실내 온도", e)
        }
        reg("registerHvacTemperatureCurrent") {
            api.registerHvacTemperatureCurrent(driverArea, temp)
            unregister += { api.unregisterHvacTemperatureCurrent(driverArea, temp) }
        }
    }

    private fun energy() {
        val ev = vehicle.getEvBattery()
        val level = object : EvBatteryLevelListener {
            override fun onEvBatteryLevelUpdated(level: Float?) { if (level != null) set { it.copy(batteryKwh = level / 1000.0) } }
            override fun onFailed(e: Exception) = warn("배터리 잔량", e)
        }
        reg("registerEvBatteryLevel") {
            ev.registerEvBatteryLevel(level)
            unregister += { ev.unregisterEvBatteryLevel(level) }
        }

        val soc = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(state: Int?) = set { it.copy(soc = state) }
            override fun onFailed(e: Exception) = warn("충전량", e)
        }
        reg("registerEvBatteryStateOfCharge") {
            ev.registerEvBatteryStateOfCharge(soc)
            unregister += { ev.unregisterEvBatteryStateOfCharge(soc) }
        }
    }

    private fun tires() {
        val api = vehicle.getTire()
        val wheels = mapOf(
            Wheel.FL to WheelValue.LEFT_FRONT, Wheel.FR to WheelValue.RIGHT_FRONT,
            Wheel.RL to WheelValue.LEFT_REAR, Wheel.RR to WheelValue.RIGHT_REAR,
        )
        for ((w, v) in wheels) {
            val area = WheelArea(v)
            val l = object : TirePressureListener {
                override fun onTirePressureUpdated(kpa: Float?) { if (kpa != null) set { it.copy(tireKpa = it.tireKpa + (w to kpa)) } }
                override fun onFailed(e: Exception) = warn("타이어 ${w.label}", e)
            }
            reg("registerTirePressure") {
                api.registerTirePressure(area, l)
                unregister += { api.unregisterTirePressure(area, l) }
            }
        }
    }

    companion object {
        private const val TAG = "PleosKit"

        /** SDK 2.3.2 좌석 탑승 반전 보정 (2026-10-29 에뮬레이터 확인). SDK 가 고쳐지면 false */
        const val SEAT_SDK_INVERTED = true
    }
}
