package com.classroom.api.teacher;

import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import java.util.Map;

public final class TeacherController {
  public HttpHandlerFunction moduleInfoHandler() {
    return exchange -> HttpResponses.json(exchange, 200, Map.of(
        "module", "teacher",
        "capabilities", new String[] {
            "manage-classrooms",
            "manage-questions",
            "control-live-session",
            "view-reports"
        }
    ));
  }
}
