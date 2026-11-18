/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.video;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Android-free role/route policy. Public logical IDs are carriers, never guessed main lenses. */
final class VideoMainCameraPolicy {
    enum SelectionFailure { NO_MAIN, AMBIGUOUS_MAIN, AMBIGUOUS_CARRIER }
    static final class SelectionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        final SelectionFailure reason;
        SelectionException(SelectionFailure reason, String message) { super(message); this.reason = reason; }
    }
    static final class Camera {
        final String id;
        final boolean back, logical;
        final Integer role;
        final int[] roles;
        final Set<String> physicalIds;
        Camera(String id, boolean back, boolean logical, Integer role, int[] roles, Set<String> physicalIds) {
            this.id = id; this.back = back; this.logical = logical;
            this.role = role; this.roles = roles; this.physicalIds = physicalIds;
        }
        int rank() {
            // Exact photo-selector precedence: authoritative roles array overrides scalar.
            if (roles != null) {
                for (int value : roles) if (value == 0) return 1;
                return 0;
            }
            return Integer.valueOf(0).equals(role) ? 2 : 0;
        }
    }
    static final class Route {
        final String openId, physicalId;
        Route(String openId, String physicalId) { this.openId = openId; this.physicalId = physicalId; }
        String targetId() { return physicalId == null ? openId : physicalId; }
    }
    static Route select(List<Camera> cameras, Set<String> publicIds) {
        List<Camera> direct = new ArrayList<>();
        for (Camera camera : cameras)
            if (camera.back && !camera.logical && publicIds.contains(camera.id) && camera.rank() > 0) direct.add(camera);
        if (!direct.isEmpty()) return new Route(uniqueBest(direct).id, null);
        List<Camera> physicalMain = new ArrayList<>();
        for (Camera target : cameras) {
            if (!target.back || target.logical || target.rank() == 0) continue;
            for (Camera parent : cameras) {
                if (parent.back && parent.logical && publicIds.contains(parent.id) && parent.physicalIds.contains(target.id)) {
                    physicalMain.add(target); break;
                }
            }
        }
        if (physicalMain.isEmpty()) throw new SelectionException(SelectionFailure.NO_MAIN, "No role=0 rear MAIN accessible directly or through a public logical camera");
        Camera target = uniqueBest(physicalMain);
        Camera carrier = null;
        for (Camera camera : cameras) {
            if (!camera.back || !camera.logical || !publicIds.contains(camera.id) || !camera.physicalIds.contains(target.id)) continue;
            if (carrier != null) throw new SelectionException(SelectionFailure.AMBIGUOUS_CARRIER, "Ambiguous public logical carriers for MAIN");
            carrier = camera;
        }
        return new Route(carrier.id, target.id);
    }
    private static Camera uniqueBest(List<Camera> candidates) {
        int best = 0;
        for (Camera camera : candidates) best = Math.max(best, camera.rank());
        Camera chosen = null;
        for (Camera camera : candidates) {
            if (camera.rank() != best) continue;
            if (chosen != null) throw new SelectionException(SelectionFailure.AMBIGUOUS_MAIN, "Ambiguous role=0 rear MAIN cameras");
            chosen = camera;
        }
        return chosen;
    }
    static boolean physicalResultMatches(String wanted, Set<String> deliveredIds) {
        return wanted == null || deliveredIds.contains(wanted);
    }
}
