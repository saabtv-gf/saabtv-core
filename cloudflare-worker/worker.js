/**
 * Saab TV Crash Report Worker
 *
 * Receives ACRA crash reports via HTTP POST and forwards them
 * to your email using Resend (https://resend.com — free: 100 emails/day).
 *
 * Setup:
 * 1. Create a free account at https://resend.com
 * 2. Get your API key from the Resend dashboard
 * 3. Add a verified domain or use the sandbox (onboarding@resend.dev)
 * 4. Deploy this worker: npx wrangler deploy
 * 5. Set the secrets:
 *    npx wrangler secret put RESEND_API_KEY
 *    npx wrangler secret put REPORT_EMAIL
 *    npx wrangler secret put AUTH_TOKEN
 * 6. Update local.properties with your worker URL
 */

export default {
  async fetch(request, env) {
    // Only accept POST to /crash-report
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/crash-report") {
      return new Response("Not found", { status: 404 });
    }

    // Basic auth check to prevent spam (ACRA sends Basic auth)
    const authHeader = request.headers.get("Authorization") || "";
    if (env.AUTH_TOKEN) {
      const expected = "Basic " + btoa("acra:" + env.AUTH_TOKEN);
      if (authHeader !== expected) {
        return new Response("Unauthorized", { status: 401 });
      }
    }

    try {
      const report = await request.json();

      // Extract key fields from the ACRA report
      const stackTrace = report.STACK_TRACE || "No stack trace";
      const appVersion = report.APP_VERSION_NAME || "unknown";
      const androidVersion = report.ANDROID_VERSION || "unknown";
      const phoneModel = report.PHONE_MODEL || "unknown";
      const brand = report.BRAND || "unknown";
      const crashDate = report.USER_CRASH_DATE || "unknown";
      const totalMemory = report.TOTAL_MEM_SIZE || "unknown";
      const availableMemory = report.AVAILABLE_MEM_SIZE || "unknown";
      const display = report.DISPLAY || "unknown";

      const subject = `Saab TV Crash - v${appVersion} - ${brand} ${phoneModel}`;

      const body = `
LUMERA CRASH REPORT
====================

Date: ${crashDate}
App Version: ${appVersion}

DEVICE INFO
-----------
Brand: ${brand}
Model: ${phoneModel}
Android: ${androidVersion}
Display: ${display}
Total Memory: ${totalMemory}
Available Memory: ${availableMemory}

STACK TRACE
-----------
${stackTrace}

FULL REPORT (JSON)
------------------
${JSON.stringify(report, null, 2)}
`.trim();

      // Send email via Resend
      const emailResponse = await fetch("https://api.resend.com/emails", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${env.RESEND_API_KEY}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          from: "Saab TV Crashes <crashes@resend.dev>",
          to: [env.REPORT_EMAIL],
          subject: subject,
          text: body,
        }),
      });

      if (!emailResponse.ok) {
        const err = await emailResponse.text();
        console.error("Resend error:", err);
        return new Response("Failed to send", { status: 500 });
      }

      return new Response("OK", { status: 200 });
    } catch (e) {
      console.error("Worker error:", e);
      return new Response("Error", { status: 500 });
    }
  },
};
