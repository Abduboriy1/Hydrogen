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
 * of a jump (vanilla 1.8 critical-hit condition). Optional Aim smoothly eases rotation
 * onto the target - Speed controls how fast, Radius the search range. Reach and CPS are
 * min/max ranges - a fresh value is rolled per crit window (reach) and per swing (CPS).
 * Selection mode locks targeting to a middle-clicked entity only.
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
    private double savedSpeed, savedSmoothness, savedPitchRatio, savedJitter;

    public PyroAxe() {
        addSetting(new Setting("ReachMin", this, 3, 3, 7, false));
        addSetting(new Setting("ReachMax", this, 4, 3, 7, false));
        addSetting(new Setting("CPSMin", this, 8, 1, 20, true));
        addSetting(new Setting("CPSMax", this, 11, 1, 20, true));
        addSetting(new Setting("Aim", this, false));
        addSetting(new Setting("AimSpeed", this, 3, 1, 10, false));
        addSetting(new Setting("Smoothness", this, 0.55, 0.1, 1, false));
        addSetting(new Setting("PitchRatio", this, 0.6, 0.1, 1, false));
        addSetting(new Setting("Jitter", this, 0.15, 0, 0.5, false));
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

    @EventTarget
    public void onUpdate(EventUpdate e) {
      try {
        if (mc.thePlayer == null || mc.theWorld == null) return;

        double reachMin = getValue("ReachMin", 3);
        double reachMax = getValue("ReachMax", 4);
        double cpsMin = getValue("CPSMin", 8);
        double cpsMax = getValue("CPSMax", 11);
        boolean aim = isEnabled("Aim", false);
        double aimSpeed = getValue("AimSpeed", 3);
        double smoothness = getValue("Smoothness", 0.55);
        double pitchRatio = getValue("PitchRatio", 0.6);
        double jitter = getValue("Jitter", 0.15);
        double radius = getValue("Radius", 5);
        boolean visibleOnly = isEnabled("VisibleOnly", true);
        double stickyTime = getValue("StickyTime", 3);
        boolean selection = isEnabled("Selection", false);
        boolean critOnly = isEnabled("CritOnly", false);
        boolean logging = isEnabled("Logging", false);

        // 1. Crit-hit state: vanilla 1.8 condition - the falling phase of a jump.
        //    Roll a fresh reach value each time a new crit window opens.
        boolean crit = mc.thePlayer.fallDistance > 0.0F
                && !mc.thePlayer.onGround
                && !mc.thePlayer.isOnLadder()
                && !mc.thePlayer.isInWater()
                && mc.thePlayer.motionY < 0;
        if (crit && critSince == 0L) {
            critSince = System.currentTimeMillis();
            currentReach = randomInRange(reachMin, reachMax);
        }
        if (!crit) critSince = 0L;
        double reach = currentReach > 0 ? currentReach : randomInRange(reachMin, reachMax);

        // 2. Crosshair target this tick (raytrace result).
        Entity crosshairEntity = (mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit instanceof EntityLivingBase)
                ? mc.objectMouseOver.entityHit : null;

        // 3. Selection mode: middle-click (LWJGL button 2) locks the crosshair target.
        //    We only read mouse state - we never consume vanilla pick-block.
        if (selection) {
            boolean middleDown = Mouse.isButtonDown(2);
            if (middleDown && !middlePrev && crosshairEntity != null) {
                selectedTarget = crosshairEntity;
                Utils.sendChatMessage("[PyroAxe] target locked: " + selectedTarget.getName());
            }
            middlePrev = middleDown;
        } else if (selectedTarget != null) {
            selectedTarget = null; // selection turned off - drop any lock
        }

        // Sticky lock expires once the window elapses or the entity dies.
        long stickyMs = (long) (stickyTime * 1000);
        boolean stickyValid = stickyTarget != null && isAlive(stickyTarget) && stickyMs > 0
                && System.currentTimeMillis() - stickySince <= stickyMs;
        if (!stickyValid) stickyTarget = null;

        // 4. Determine the tracked entity.
        //    Selection ON  -> only the locked entity is ever a target (no fallback).
        //    Selection OFF -> sticky lock (recent hit), then crosshair, then nearest.
        Entity target;
        if (selection) {
            target = selectedTarget;
        } else if (stickyValid) {
            target = stickyTarget;
        } else {
            target = crosshairEntity;
            if (target == null && aim) target = findNearest(radius, visibleOnly);
        }

        boolean crosshairOver;
        boolean reachable = false;
        double distance = -1;

        if (isAlive(target)) {
            distance = mc.thePlayer.getDistanceToEntity(target);
            reachable = distance <= reach;
            crosshairOver = target == crosshairEntity;
        } else {
            crosshairOver = false;
            if (selection && selectedTarget != null && !isAlive(selectedTarget)) {
                Utils.sendChatMessage("[PyroAxe] selected target removed");
                selectedTarget = null;
            }
            target = null; // dead/invalid - stop tracking immediately
        }

        // 5. Aim: hand the target to the render pass so the real camera turns (applying
        //    rotation here is silent-only - the tick pipeline restores it post-packet).
        //    Skip when VisibleOnly is set and blocks obstruct line-of-sight.
        if (aim && isAlive(target) && (!visibleOnly || mc.thePlayer.canEntityBeSeen(target))) {
            aimTarget = target;
            savedSpeed = aimSpeed;
            savedSmoothness = smoothness;
            savedPitchRatio = pitchRatio;
            savedJitter = jitter;
        } else {
            aimTarget = null;
        }

        // 6. Opportunity state. With CritOnly, a non-crit tick is never an opportunity.
        //    crosshairOver is required: we only ever hit the entity the real crosshair is
        //    actually on - never a silent hit on a sticky/nearest target off-center. Aim (if
        //    enabled) turns the live camera onto the target, after which it becomes the
        //    crosshair entity and this gate opens legitimately.
        boolean ready = crit && reachable && isAlive(target) && crosshairOver;
        if (critOnly && !crit) {
            if (lastReady && logging) Utils.sendChatMessage("[PyroAxe] opportunity lost");
            lastReady = false;
            return;
        }

        // 7. Attack: swing + send attack packet, throttled to a CPS rolled from the range.
        if (ready) {
            double cps = randomInRange(cpsMin, cpsMax);
            if (cps < 1) cps = 1;
            long delay = Math.round(1000.0 / cps);
            if (time.isDelayComplete(delay) && mc.playerController != null) {
                mc.thePlayer.swingItem();
                mc.playerController.attackEntity(mc.thePlayer, target);
                time.setLastMS();
                // Non-selection: first hit locks this target for the sticky window.
                if (!selection && stickyTime > 0) {
                    stickyTarget = target;
                    stickySince = System.currentTimeMillis();
                }
            }
        }

        // 8. Optional diagnostic logging on opportunity-state transitions.
        if (logging && ready != lastReady) {
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
        lastReady = ready;
      } catch (Exception ex) {
        aimTarget = null;
        if (isEnabled("Logging", false)) Utils.sendChatMessage("[PyroAxe] error: " + ex);
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
        aimAt(aimTarget, e.getPartialTicks(), savedSpeed, savedSmoothness, savedPitchRatio, savedJitter);
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

    /**
     * Move the head toward the target's eyes with human-like motion. {@code speed} is the
     * max degrees turned per tick. Yaw leads, pitch trails by {@code pitchRatio} (vertical
     * aim is slower for a real player); each axis eases out by {@code smoothness} near the
     * target and carries {@code jitter} so the motion isn't perfectly linear.
     */
    private void aimAt(Entity target, float partialTicks, double speed, double smoothness, double pitchRatio, double jitter) {
        Vec3 self = mc.thePlayer.getPositionEyes(partialTicks);
        Vec3 tgt = target.getPositionEyes(partialTicks);
        double diffX = tgt.xCoord - self.xCoord;
        double diffY = tgt.yCoord - self.yCoord;
        double diffZ = tgt.zCoord - self.zCoord;
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float targetYaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(diffY, diffXZ));

        float yawCap = (float) speed;
        float pitchCap = (float) (speed * pitchRatio); // vertical lags horizontal

        mc.thePlayer.rotationYaw = smoothHeadMovement(mc.thePlayer.rotationYaw, targetYaw, yawCap, smoothness, jitter);
        mc.thePlayer.rotationPitch = smoothHeadMovement(mc.thePlayer.rotationPitch, targetPitch, pitchCap, smoothness, jitter);
        mc.thePlayer.rotationPitch = MathHelper.clamp_float(mc.thePlayer.rotationPitch, -90F, 90F);
    }

    /**
     * Transition one angle toward another with natural inertia: ease out by
     * {@code smoothness} as the gap closes, cap the per-tick turn at {@code speed}, and add
     * {@code jitter} so the path looks hand-aimed rather than mechanical.
     */
    private float smoothHeadMovement(float current, float target, float speed, double smoothness, double jitter) {
        float diff = target - current;

        // Normalize to -180..180 so we turn the short way around.
        while (diff <= -180.0F) diff += 360.0F;
        while (diff > 180.0F) diff -= 360.0F;

        // Ease-out: cover a fraction of the remaining gap, decelerating near the target.
        float step = diff * (float) smoothness;

        // Inertia: never turn more than the per-tick speed cap.
        if (step > speed) step = speed;
        if (step < -speed) step = -speed;

        // Human imperfection: small jitter scaled to how far we still move.
        step += (random.nextFloat() - 0.5F) * speed * (float) jitter;

        return current + step;
    }
}
