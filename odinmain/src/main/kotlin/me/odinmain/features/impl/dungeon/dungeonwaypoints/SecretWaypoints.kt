package me.odinmain.features.impl.dungeon.dungeonwaypoints

import me.odinmain.config.DungeonWaypointConfig
import me.odinmain.events.impl.SecretPickupEvent
import me.odinmain.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.WaypointType
import me.odinmain.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.TimerType
import me.odinmain.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.getWaypoints
import me.odinmain.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import me.odinmain.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.glList
import me.odinmain.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.lastEtherPos
import me.odinmain.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.lastEtherTime
import me.odinmain.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.setWaypoints
import me.odinmain.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.toVec3
import me.odinmain.utils.*
import me.odinmain.utils.skyblock.devMessage
import me.odinmain.utils.skyblock.dungeon.DungeonUtils
import me.odinmain.utils.skyblock.dungeon.DungeonUtils.getRelativeCoords
import me.odinmain.utils.skyblock.dungeon.tiles.FullRoom
import me.odinmain.utils.skyblock.modMessage
import net.minecraft.block.BlockChest
import net.minecraft.block.state.IBlockState
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.util.BlockPos
import net.minecraft.util.Vec3

object SecretWaypoints {

    private var checkpoints: Int = 0
    private var routeTimer: Long? = null
    private var lastClicked: BlockPos? = null

    fun onLocked() {
        val room = DungeonUtils.currentFullRoom ?: return
        val vec = room.getRelativeCoords(lastClicked?.toVec3() ?: return)
        getWaypoints(room).find { wp -> wp.toVec3().equal(vec) && wp.secret && wp.clicked }?.let {
            it.clicked = false
            setWaypoints(room)
            devMessage("unclicked ${it.toVec3()}")
            glList = -1
            lastClicked = null
        }
    }

    fun onSecret(event: SecretPickupEvent) {
        when (event) {
            is SecretPickupEvent.Interact -> clickSecret(event.blockPos.toVec3(), 0, event.blockState)
            is SecretPickupEvent.Bat -> clickSecret(event.packet.positionVector, 5)
            is SecretPickupEvent.Item -> clickSecret(event.entity.positionVector, 3)
        }
    }

    fun onEtherwarp(packet: S08PacketPlayerPosLook) {
        if (!DungeonUtils.inDungeons) return
        val etherpos = lastEtherPos?.pos?.toVec3() ?: return
        if (System.currentTimeMillis() - lastEtherTime > 1000) return
        val pos = Vec3(packet.x, packet.y, packet.z)
        if (pos.distanceTo(etherpos) > 3) return
        val room = DungeonUtils.currentFullRoom ?: return
        val vec = room.getRelativeCoords(etherpos)
        val waypoints = getWaypoints(room)
        waypoints.find { wp -> wp.toVec3().equal(vec) && wp.type == WaypointType.ETHERWARP }?.let {
            handleTimer(it, waypoints, room)
            it.clicked = true
            setWaypoints(room)
            glList = -1
            lastEtherTime = 0L
            lastEtherPos = null
        }
    }

    private fun clickSecret(pos: Vec3, distance: Int, block: IBlockState? = null) {
        val room = DungeonUtils.currentFullRoom ?: return
        val vec = room.getRelativeCoords(pos)

        val waypoints = getWaypoints(room)
        val waypoint = if (distance == 0) getWaypoints(room).find { wp -> wp.toVec3().equal(vec) && wp.secret && !wp.clicked }
        else waypoints.minByOrNull { wp ->
            if (wp.secret && !wp.clicked) wp.toVec3().distanceTo(vec).takeIf { it <= distance } ?: Double.MAX_VALUE
            else Double.MAX_VALUE
        }

        waypoint?.let {
            handleTimer(it, waypoints, room)
            if (block?.block is BlockChest) lastClicked = BlockPos(pos)
            it.clicked = true
            setWaypoints(room)
            devMessage("clicked ${it.toVec3()}")
            glList = -1
        }
    }

    fun resetSecrets() {
        DungeonWaypointConfig.waypoints.entries.forEach { (_, room) ->
            room.forEach { it.clicked = false }
        }

        DungeonUtils.currentFullRoom?.let { setWaypoints(it) }
        glList = -1
    }

    fun onPosUpdate(pos: Vec3) {
        val room = DungeonUtils.currentFullRoom ?: return
        val vec = room.getRelativeCoords(pos)

        val waypoints = getWaypoints(room)
        waypoints.find { wp -> wp.toVec3().addVec(y = 0.5).distanceTo(vec) <= 2 && wp.type == WaypointType.MOVE }?.let { wp ->
            wp.timer?.let { if (handleTimer(wp, waypoints, room)) wp.clicked = true } ?: run { wp.clicked = true }
            setWaypoints(room)
            devMessage("clicked ${wp.toVec3()}")
            glList = -1
        }
    }

    private fun handleTimer(waypoint: DungeonWaypoint, waypoints: MutableList<DungeonWaypoint>, room: FullRoom): Boolean {
        return when {
            waypoint.timer == TimerType.START && (routeTimer?.let { System.currentTimeMillis() - it >= 2000 } == true || routeTimer == null) -> {
                modMessage("${routeTimer?.let { "Route timer restarted" } ?: "Route timer started"} ")
                waypoints.forEach { if (it.timer == TimerType.CHECKPOINT) it.clicked = false }
                routeTimer = System.currentTimeMillis()
                true
            }
            waypoint.timer == TimerType.END && routeTimer != null -> {
                modMessage("Route took ${routeTimer?.let { (System.currentTimeMillis() - it)/1000.0 }?.round(2)}s to complete! Room: ${room.room.data.name}, Checkpoints collected: ${checkpoints}${waypoint.title?.let { name -> ", Route: $name" } ?: "."}")
                routeTimer = null
                checkpoints = 0
                true
            }
            waypoint.timer == TimerType.CHECKPOINT && !waypoint.clicked && routeTimer != null -> {
                modMessage("Collected a checkpoint at ${routeTimer?.let { (System.currentTimeMillis() - it)/1000.0 }?.round(2)}.")
                checkpoints++
                true
            }
            else -> false
        }
    }
}