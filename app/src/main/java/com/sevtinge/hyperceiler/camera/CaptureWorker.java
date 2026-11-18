/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.camera;

import android.content.Context;

/** Worker lifecycle seam; service owns durable admission and serial completion. */
interface CaptureWorker {
    interface Completion { void finished(CaptureWorker worker, boolean success, String reason, boolean retryable); }
    interface Factory {
        CaptureWorker create(Context context, long deadline, CaptureRequestQueue queue,
                CaptureRequestQueue.Request request, Completion completion);
    }
    void start();
    void cancel(String reason);
}
