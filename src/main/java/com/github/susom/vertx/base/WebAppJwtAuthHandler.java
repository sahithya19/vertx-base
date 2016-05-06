/*
 * Copyright 2016 The Board of Trustees of The Leland Stanford Junior University.
 * All Rights Reserved.
 *
 * See the NOTICE and LICENSE files distributed with this work for information
 * regarding copyright ownership and licensing. You may not use this file except
 * in compliance with a written license agreement with Stanford University.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See your
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.github.susom.vertx.base;

import com.github.susom.database.Metric;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static io.vertx.core.http.HttpHeaders.SET_COOKIE;

/**
 * This handler uses JWT tokens from a browser cookie ("access_token")
 * to authenticate the user. It supports both optional and mandatory
 * authentication, so you can put an optional one in front of everything
 * to get attributed logging where possible, and a mandatory handler
 * in front of your protected resources.
 *
 * <p>This handler manages the SLF4J MDC context by populating the "userId"
 * (based on authentication) and "windowId" (based on the X-WINDOW-ID header,
 * if present). These are generally not cleared by this handler, as that is
 * left to the MetricsHandler after it finishes logging the response.</p>
 *
 * <p>If enforcement is mandatory, a 401 response will be returned if
 * authentication did not succeed for any reason.</p>
 *
 * <p>If enforcement is mandatory, it will also perform XSRF defenses,
 * matching the cookie named XSRF-TOKEN (if present) against the header
 * named X-XSRF-TOKEN. A 403 response will be returned if the header
 * was not provided or did not match the cookie.</p>
 */
public class WebAppJwtAuthHandler implements Handler<RoutingContext> {
  private static final Logger log = LoggerFactory.getLogger(WebAppJwtAuthHandler.class);
  private final JWTAuth jwt;
  private final boolean mandatory;

  private WebAppJwtAuthHandler(JWTAuth jwt, boolean mandatory) {
    this.jwt = jwt;
    this.mandatory = mandatory;
  }

  public static WebAppJwtAuthHandler optional(JWTAuth jwt) {
    return new WebAppJwtAuthHandler(jwt, false);
  }

  public static WebAppJwtAuthHandler mandatory(JWTAuth jwt) {
    return new WebAppJwtAuthHandler(jwt, true);
  }

  public void handle(RoutingContext rc) {
    User user = rc.user();
    if (user != null) {
      String userId = user.principal().getString("sub");
      if (!userId.equals(MDC.get("userId"))) {
        log.warn("User from routing context (" + userId + ") did not match logging context ("
            + MDC.get("userId") + ")");
        MDC.put("userId", userId);
      }
      rc.next();
    } else {
      MDC.remove("userId");

      Metric metric = MetricsHandler.metricFor(rc);

      String windowId = rc.request().getHeader("X-WINDOW-ID");
      if (windowId != null && windowId.matches("[a-zA-Z0-9]{1,32}")) {
        MDC.put("windowId", windowId);
      } else {
        MDC.remove("windowId");
      }

      if (mandatory) {
        Cookie xsrf = rc.getCookie("XSRF-TOKEN");
        if (xsrf != null) {
          String xsrfHeader = rc.request().getHeader("X-XSRF-TOKEN");
          if (xsrfHeader == null || xsrfHeader.length() == 0) {
            log.debug("Missing XSRF header");
            rc.response().setStatusCode(403).end("Send X-XSRF-TOKEN header with value from XSRF-TOKEN cookie");
            return;
          } else if (!xsrf.getValue().equals(xsrfHeader)) {
            log.debug("XSRF header did not match");
            rc.response().setStatusCode(403).end("The X-XSRF-TOKEN header value did not match the XSRF-TOKEN cookie");
            return;
          }
        }
      }

      Cookie session = rc.getCookie("access_token");
      if (session != null && session.getValue() != null) {
        jwt.authenticate(new JsonObject().put("jwt", session.getValue()), r -> {
          if (r.succeeded()) {
            metric.checkpoint("auth");
            String userId = r.result().principal().getString("sub");
            rc.setUser(r.result());
            MDC.put("userId", userId);
            rc.next();
          } else {
            metric.checkpoint("authFail");
            rc.response().headers().add(SET_COOKIE, session.setValue("").setMaxAge(0).encode());
            if (mandatory) {
              log.debug("Access token could not be authenticated", r.cause());
              rc.response().setStatusCode(401).end("Access token expired");
            } else {
              rc.next();
            }
          }
        });
      } else {
        metric.checkpoint("noAuth");
        if (mandatory) {
          rc.response().setStatusCode(401).end("No access_token cookie");
        } else {
          rc.next();
        }
      }
    }
  }
}