package com.saab.tv.remote_input

import fi.iki.elonen.NanoHTTPD

/**
 * Shared disconnect detection banner for all local web servers.
 * Inject [htmlSnippet] before </body> in every served HTML page,
 * and handle the "/ping" route with [pingResponse] in each server.
 */
object DisconnectBanner {

    /** Returns a 200 OK plain-text response for the /ping endpoint. */
    fun pingResponse(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "pong")

    /**
     * HTML + CSS + JS snippet to inject before </body>.
     * Pings the server every 3s and shows a fullscreen overlay when unreachable.
     */
    val htmlSnippet: String = """
        <div id="dc-banner" style="display:none;position:fixed;inset:0;z-index:9999;background:rgba(0,0,0,0.85);display:none;align-items:center;justify-content:center;flex-direction:column;">
            <div style="text-align:center;">
                <div style="font-size:2rem;margin-bottom:12px;">⚠️</div>
                <div style="color:#fff;font-size:18px;font-weight:600;">Disconnected</div>
                <div style="color:rgba(255,255,255,0.5);font-size:14px;margin-top:8px;">Waiting for connection...</div>
            </div>
        </div>
        <script>
        (function(){
            var banner=document.getElementById('dc-banner');
            var alive=true;
            function check(){
                fetch('/ping').then(function(r){
                    if(!r.ok)throw 0;
                    if(!alive){alive=true;banner.style.display='none';}
                }).catch(function(){
                    if(alive){alive=false;banner.style.display='flex';}
                });
            }
            setInterval(check,3000);
        })();
        </script>
    """.trimIndent()
}
