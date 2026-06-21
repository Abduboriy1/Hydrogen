package me.peanut.hydrogen.module.modules.combat;

import com.darkmagician6.eventapi.EventTarget;
import me.peanut.hydrogen.events.EventRender3D;
import me.peanut.hydrogen.events.EventUpdate;
import me.peanut.hydrogen.module.Category;
import me.peanut.hydrogen.module.Info;
import me.peanut.hydrogen.module.Module;
import me.peanut.hydrogen.settings.Setting;
import me.peanut.hydrogen.utils.TimeUtils;
import me.peanut.hydrogen.utils.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.Random;

/**
 * Crit-axe combat module. Attacks a reachable living entity during the falling phase
 * of a jump (vanilla 1.8 critical-hit condition). Reach and CPS are min/max ranges - a
 * fresh value is rolled per crit window (reach) and per swing (CPS). Selection mode locks
 * targeting to a middle-clicked entity only.
 *
 * Optional Aim (ported from fusion's aim assist): yaw always tracks the target, smoothed
 * by a divisor (Smooth - bigger is slower). Pitch is boxed to the target's body via a
 * head pitch and a feet pitch (the head-foot technique) - within that range vertical aim
 * is left free, only past the head or feet is pitch pulled back to the nearest edge.
 * RandomYaw/RandomPitch add per-tick noise; Adaptive biases the yaw offset with the A/D
 * strafe keys; GCD snaps rotation deltas to the mouse-sensitivity grid so they read like
 * real mouse input (a rotation-analysis bypass the reference didn't have). AimFOV gates
 * how far off-crosshair a target may be before the aim engages.
 */
@Info(name = "PyroAxe", description = "Auto-attacks on crit-hit opportunities", category = Category.Combat)
public class PyroAxe extends Module {

    private Entity selectedTarget;
    private Entity stickyTarget;
    private long stickySince;
    private boolean lastReady;
    private boolean middlePrev;
    private long critSince;
    private double currentReach;
    private final TimeUtils time = new TimeUtils();
    private final Random random = new Random();

    // Aim state handed from the tick to the render pass (where the camera actually turns).
    private Entity aimTarget;
    private double savedSmooth, savedRandomYaw, savedRandomPitch, savedAdaptiveOffset;
    private boolean savedAdaptive, savedGcd;

    public PyroAxe() {
        addSetting(new Setting("ReachMin", this, 3, 3, 7, false));
        addSetting(new Setting("ReachMax", this, 4, 3, 7, false));
        addSetting(new Setting("CPSMin", this, 8, 1, 20, true));
        addSetting(new Setting("CPSMax", this, 11, 1, 20, true));
        addSetting(new Setting("Aim", this, false));
        addSetting(new Setting("AimFOV", this, 40, 5, 180, false));
        addSetting(new Setting("Smooth", this, 18, 1, 90, false));
        addSetting(new Setting("RandomYaw", this, 2, 0, 10, false));
        addSetting(new Setting("RandomPitch", this, 0.15, 0, 1, false));
        addSetting(new Setting("Adaptive", this, false));
        addSetting(new Setting("AdaptiveOffset", this, 3, 0.1, 15, false));
        addSetting(new Setting("GCD", this, true));
        addSetting(new Setting("Radius", this, 5, 3, 12, false));
        addSetting(new Setting("VisibleOnly", this, true));
        addSetting(new Setting("StickyTime", this, 3, 0, 10, false));
        addSetting(new Setting("Selection", this, false));
        addSetting(new Setting("CritOnly", this, false));
        addSetting(new Setting("Logging", this, false));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        resetState();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        resetState();
    }

    /** Clear all target/timing state so toggling the module starts fresh. */
    private void resetState() {
        selectedTarget = null;
        stickyTarget = null;
        aimTarget = null;
        stickySince = 0L;
        lastReady = false;
        middlePrev = false;
        critSince = 0L;
        currentReach = 0;
    }

    /**
     * Per-tick orchestrator. Each numbered phase lives in its own method below; this just
     * wires them together and keeps the crash-guard around the whole pass. The reachability
     * check and the {@code ready} gate stay inline because they tie the phases together.
     */
    @EventTarget
    public void onUpdate(EventUpdate e) {
      try {
        if (mc.thePlayer == null || mc.theWorld == null) return;

        Config c = readConfig();

        // 1. Crit-hit window (vanilla 1.8 falling-phase condition) + fresh reach roll.
        boolean crit = inCritWindow();
        updateCritWindow(crit, c);
        double reach = currentReach > 0 ? currentReach : randomInRange(c.reachMin, c.reachMax);

        // 2-4. Resolve the tracked entity from crosshair / selection / sticky / nearest.
        Entity crosshair = crosshairEntity();
        updateSelection(c, crosshair);
        boolean stickyValid = refreshSticky(c);
        Entity target = resolveTarget(c, crosshair, stickyValid);

        // Reachability glue: feeds both the attack gate and the logging line.
        boolean crosshairOver;
        boolean reachable = false;
        double distance = -1;
        if (isAlive(target)) {
            distance = mc.thePlayer.getDistanceToEntity(target);
            reachable = distance <= reach;
            crosshairOver = target == crosshair;
        } else {
            crosshairOver = false;
            if (c.selection && selectedTarget != null && !isAlive(selectedTarget)) {
                Utils.sendChatMessage("[PyroAxe] selected target removed");
                selectedTarget = null;
            }
            target = null; // dead/invalid - stop tracking immediately
        }

        // 5. Hand the target to the render pass for the live aim.
        updateAim(c, target);

        // 6. Opportunity gate. crosshairOver is required: we only ever hit the entity the
        //    real crosshair is on - never a silent hit on a sticky/nearest off-center target.
        boolean ready = crit && reachable && isAlive(target) && crosshairOver;
        if (c.critOnly && !crit) {
            if (lastReady && c.logging) Utils.sendChatMessage("[PyroAxe] opportunity lost");
            lastReady = false;
            return;
        }

        // 7. Attack, throttled to a CPS rolled from the range.
        if (ready) tryAttack(c, target);

        // 8. Diagnostic logging on opportunity-state transitions.
        logTransition(c, ready, target, distance, reach, crosshairOver);
        lastReady = ready;
      } catch (Exception ex) {
        aimTarget = null;
        if (isEnabled("Logging", false)) Utils.sendChatMessage("[PyroAxe] error: " + ex);
      }
    }

    /** True during the vanilla 1.8 critical-hit window: the falling phase of a jump. */
    private boolean inCritWindow() {
        return mc.thePlayer.fallDistance > 0.0F
                && !mc.thePlayer.onGround
                && !mc.thePlayer.isOnLadder()
                && !mc.thePlayer.isInWater()
                && mc.thePlayer.motionY < 0;
    }

    /**
     * Track the crit window's lifetime. On the tick a fresh window opens, stamp {@code critSince}
     * and roll a new reach value (held for the whole window); clear the stamp once it closes.
     */
    private void updateCritWindow(boolean crit, Config c) {
        if (crit && critSince == 0L) {
            critSince = System.currentTimeMillis();
            currentReach = randomInRange(c.reachMin, c.reachMax);
        }
        if (!crit) critSince = 0L;
    }

    /** The living entity under the crosshair this tick (raytrace result), or null. */
    private Entity crosshairEntity() {
        return (mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit instanceof EntityLivingBase)
                ? mc.objectMouseOver.entityHit : null;
    }

    /**
     * Selection mode: middle-click (LWJGL button 2) locks the crosshair entity as the only
     * target. We only read mouse state - never consume vanilla pick-block. With selection off,
     * drop any existing lock.
     */
    private void updateSelection(Config c, Entity crosshair) {
        if (c.selection) {
            boolean middleDown = Mouse.isButtonDown(2);
            if (middleDown && !middlePrev && crosshair != null) {
                selectedTarget = crosshair;
                Utils.sendChatMessage("[PyroAxe] target locked: " + selectedTarget.getName());
            }
            middlePrev = middleDown;
        } else if (selectedTarget != null) {
            selectedTarget = null; // selection turned off - drop any lock
        }
    }

    /** Validate the sticky lock (alive + within window), expiring it otherwise. */
    private boolean refreshSticky(Config c) {
        long stickyMs = (long) (c.stickyTime * 1000);
        boolean stickyValid = stickyTarget != null && isAlive(stickyTarget) && stickyMs > 0
                && System.currentTimeMillis() - stickySince <= stickyMs;
        if (!stickyValid) stickyTarget = null;
        return stickyValid;
    }

    /**
     * Pick the tracked entity. Selection ON -> only the locked entity (no fallback).
     * Selection OFF -> sticky lock (recent hit), then crosshair, then nearest (aim only).
     */
    private Entity resolveTarget(Config c, Entity crosshair, boolean stickyValid) {
        if (c.selection) return selectedTarget;
        if (stickyValid) return stickyTarget;
        Entity target = crosshair;
        if (target == null && c.aim) target = findNearest(c.radius, c.visibleOnly);
        return target;
    }

    /**
     * Aim: hand the target to the render pass so the real camera turns (applying rotation here
     * is silent-only - the tick pipeline restores it post-packet). Skip when VisibleOnly is set
     * and blocks obstruct line-of-sight, or the target sits outside the FOV gate.
     */
    private void updateAim(Config c, Entity target) {
        if (c.aim && isAlive(target) && (!c.visibleOnly || mc.thePlayer.canEntityBeSeen(target))
                && withinFov(target, c.aimFov)) {
            aimTarget = target;
            savedSmooth = c.smooth;
            savedRandomYaw = c.randomYaw;
            savedRandomPitch = c.randomPitch;
            savedAdaptive = c.adaptive;
            savedAdaptiveOffset = c.adaptiveOffset;
            savedGcd = c.gcd;
        } else {
            aimTarget = null;
        }
    }

    /** Swing + send the attack packet, throttled to a CPS rolled from the range. */
    private void tryAttack(Config c, Entity target) {
        double cps = randomInRange(c.cpsMin, c.cpsMax);
        if (cps < 1) cps = 1;
        long delay = Math.round(1000.0 / cps);
        if (time.isDelayComplete(delay) && mc.playerController != null) {
            mc.thePlayer.swingItem();
            mc.playerController.attackEntity(mc.thePlayer, target);
            time.setLastMS();
            // Non-selection: first hit locks this target for the sticky window.
            if (!c.selection && c.stickyTime > 0) {
                stickyTarget = target;
                stickySince = System.currentTimeMillis();
            }
        }
    }

    /** Optional diagnostic logging, fired only on opportunity-state transitions. */
    private void logTransition(Config c, boolean ready, Entity target, double distance,
                               double reach, boolean crosshairOver) {
        if (!c.logging || ready == lastReady) return;
        if (ready) {
            long heldMs = critSince == 0L ? 0L : System.currentTimeMillis() - critSince;
            Utils.sendChatMessage(String.format(
                    "[PyroAxe] CRIT READY | target=%s dist=%.2f reach<=%.1f crosshair=%b | onGround=%b motionY=%.3f fall=%.2f sprint=%b sneak=%b | heldMs=%d",
                    target == null ? "none" : target.getName(),
                    distance, reach, crosshairOver,
                    mc.thePlayer.onGround, mc.thePlayer.motionY, mc.thePlayer.fallDistance,
                    mc.thePlayer.isSprinting(), mc.thePlayer.isSneaking(), heldMs));
        } else {
            Utils.sendChatMessage("[PyroAxe] opportunity lost");
        }
    }

    /** Immutable snapshot of every setting, read once per tick (null-safe via getValue/isEnabled). */
    private Config readConfig() {
        return new Config(this);
    }

    /** Holds all 18 setting values for one tick so phase methods don't pass long param lists. */
    private final class Config {
        final double reachMin, reachMax, cpsMin, cpsMax, aimFov, smooth,
                randomYaw, randomPitch, adaptiveOffset, radius, stickyTime;
        final boolean aim, adaptive, gcd, visibleOnly, selection, critOnly, logging;

        Config(PyroAxe m) {
            reachMin = m.getValue("ReachMin", 3);
            reachMax = m.getValue("ReachMax", 4);
            cpsMin = m.getValue("CPSMin", 8);
            cpsMax = m.getValue("CPSMax", 11);
            aim = m.isEnabled("Aim", false);
            aimFov = m.getValue("AimFOV", 40);
            smooth = m.getValue("Smooth", 18);
            randomYaw = m.getValue("RandomYaw", 2);
            randomPitch = m.getValue("RandomPitch", 0.15);
            adaptive = m.isEnabled("Adaptive", false);
            adaptiveOffset = m.getValue("AdaptiveOffset", 3);
            gcd = m.isEnabled("GCD", true);
            radius = m.getValue("Radius", 5);
            visibleOnly = m.isEnabled("VisibleOnly", true);
            stickyTime = m.getValue("StickyTime", 3);
            selection = m.isEnabled("Selection", false);
            critOnly = m.isEnabled("CritOnly", false);
            logging = m.isEnabled("Logging", false);
        }
    }

    /**
     * Apply the head movement during rendering. Done here (not in the tick) so the live
     * camera actually turns - the tick pipeline restores rotation after the packet, which
     * would otherwise make the aim silent.
     */
    @EventTarget
    public void onRender(EventRender3D e) {
      try {
        if (aimTarget == null || mc.thePlayer == null) return;
        if (!isAlive(aimTarget)) {
            aimTarget = null;
            return;
        }
        aimAt(aimTarget, e.getPartialTicks(), savedSmooth, savedRandomYaw, savedRandomPitch,
                savedAdaptive, savedAdaptiveOffset, savedGcd);
      } catch (Exception ex) {
        aimTarget = null;
        if (isEnabled("Logging", false)) Utils.sendChatMessage("[PyroAxe] error: " + ex);
      }
    }

    /** Setting value, or {@code def} if the setting is missing (null-safe lookup). */
    private double getValue(String name, double def) {
        Setting s = h2.settingsManager.getSettingByName(this, name);
        return s != null ? s.getValue() : def;
    }

    /** Boolean setting state, or {@code def} if the setting is missing (null-safe lookup). */
    private boolean isEnabled(String name, boolean def) {
        Setting s = h2.settingsManager.getSettingByName(this, name);
        return s != null ? s.isEnabled() : def;
    }

    /** Random double in [min, max]; tolerates min/max being supplied out of order. */
    private double randomInRange(double min, double max) {
        if (max < min) {
            double t = min;
            min = max;
            max = t;
        }
        if (max == min) return min;
        return min + random.nextDouble() * (max - min);
    }

    /**
     * Nearest living entity (excluding self) within range, by eye distance. When
     * {@code visibleOnly} is set, entities with no clear line-of-sight are skipped.
     */
    private Entity findNearest(double range, boolean visibleOnly) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        // Snapshot the list: the world/network thread mutates loadedEntityList and iterating
        // it live can throw ConcurrentModificationException.
        for (Entity entity : new ArrayList<>(mc.theWorld.loadedEntityList)) {
            try {
                if (entity == mc.thePlayer || !isAlive(entity)) continue;
                double dist = eyes.distanceTo(entity.getPositionEyes(1.0F));
                if (dist <= range && dist < bestDist
                        && (!visibleOnly || mc.thePlayer.canEntityBeSeen(entity))) {
                    bestDist = dist;
                    best = entity;
                }
            } catch (Exception ignored) {
                // Skip any entity that misbehaves rather than aborting the whole search.
            }
        }
        return best;
    }

    /**
     * A target is valid only while truly alive. {@code isDead} lags ~1s behind death
     * (death animation), so we also require positive health / isEntityAlive - this drops
     * the lock the instant the entity is killed instead of tracking the corpse.
     */
    private boolean isAlive(Entity entity) {
        return entity instanceof EntityLivingBase
                && entity.isEntityAlive()
                && ((EntityLivingBase) entity).getHealth() > 0F;
    }

    /** Yaw to the target's horizontal position; -90 maps atan2 into MC's yaw convention. */
    private float yawTo(Entity target) {
        double dx = target.posX - mc.thePlayer.posX;
        double dz = target.posZ - mc.thePlayer.posZ;
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
    }

    /** True if the target is within {@code fov} degrees of the current yaw (fusion's gate). */
    private boolean withinFov(Entity target, double fov) {
        float diff = MathHelper.wrapAngleTo180_float(yawTo(target) - mc.thePlayer.rotationYaw);
        return Math.abs(diff) <= fov;
    }

    /**
     * Move the head toward the target, ported from fusion's aim assist.
     *
     * Yaw always tracks the target, smoothed by the {@code smooth} divisor (bigger is
     * slower). Pitch is boxed to the target's body via a head pitch and a feet pitch: while
     * the player's pitch sits between them vertical aim is left free (only a touch of
     * {@code randomPitch} noise), and only past the head (up) or feet (down) is pitch eased
     * back to the nearest edge. {@code randomYaw} adds per-tick yaw noise; {@code adaptive}
     * biases the yaw offset with the A/D strafe keys; {@code gcd} snaps the resulting
     * rotation deltas to the mouse-sensitivity grid so they look like real mouse input.
     */
    private void aimAt(Entity target, float partialTicks, double smooth, double randomYaw, double randomPitch,
                       boolean adaptive, double adaptiveOffset, boolean gcd) {
        Vec3 self = mc.thePlayer.getPositionEyes(partialTicks);

        // Partial-tick interpolated target position for smooth render-time aim.
        double tx = target.lastTickPosX + (target.posX - target.lastTickPosX) * partialTicks;
        double ty = target.lastTickPosY + (target.posY - target.lastTickPosY) * partialTicks;
        double tz = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * partialTicks;
        double height = target.getEyeHeight() - 0.1; // fusion: target.height - 0.1

        double dx = tx - self.xCoord;
        double dz = tz - self.zCoord;
        double dxz = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        // MC convention: looking down is +pitch, so head (higher) is the smaller bound.
        float pitchFoot = (float) -Math.toDegrees(Math.atan2(ty - self.yCoord, dxz));
        float pitchHead = (float) -Math.toDegrees(Math.atan2(ty + height - self.yCoord, dxz));

        float curYaw = mc.thePlayer.rotationYaw;
        float curPitch = mc.thePlayer.rotationPitch;

        // Yaw: always track, divisor smoothing, random + strafe-adaptive offset.
        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - curYaw);
        float offset = (random.nextFloat() * 2F - 1F) * (float) randomYaw;
        if (adaptive) {
            boolean a = mc.gameSettings.keyBindLeft.isKeyDown();   // strafe left
            boolean d = mc.gameSettings.keyBindRight.isKeyDown();  // strafe right
            if (d && !a) offset -= (float) adaptiveOffset;
            if (a && !d) offset += (float) adaptiveOffset;
        }
        float newYaw = curYaw + (yawDiff + offset) / (float) smooth;

        // Pitch: free within the head-foot box, eased to the nearest edge once outside it.
        float newPitch;
        if (curPitch > pitchFoot || curPitch < pitchHead) {
            float dFoot = Math.abs(MathHelper.wrapAngleTo180_float(pitchFoot - curPitch));
            float dHead = Math.abs(MathHelper.wrapAngleTo180_float(pitchHead - curPitch));
            float bound = dFoot < dHead ? pitchFoot : pitchHead;
            newPitch = curPitch + MathHelper.wrapAngleTo180_float(bound - curPitch) / (float) smooth;
        } else {
            newPitch = curPitch;
        }
        newPitch += (random.nextFloat() * 2F - 1F) * (float) randomPitch;

        // GCD: round the rotation deltas to the mouse-sensitivity grid (rotation bypass).
        if (gcd) {
            float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
            float g = f * f * f * 8.0F;
            newYaw = curYaw + Math.round((newYaw - curYaw) / g) * g;
            newPitch = curPitch + Math.round((newPitch - curPitch) / g) * g;
        }

        mc.thePlayer.rotationYaw = newYaw;
        mc.thePlayer.rotationPitch = MathHelper.clamp_float(newPitch, -90F, 90F);
    }
}
