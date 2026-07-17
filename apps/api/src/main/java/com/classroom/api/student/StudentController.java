package com.classroom.api.student;

import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import java.util.Map;

public final class StudentController {
  public HttpHandlerFunction moduleInfoHandler() {
    return exchange -> HttpResponses.json(exchange, 200, Map.of(
        "module", "student",
        "capabilities", new String[] {
            "join-classroom",
            "receive-presence",
            "answer-questions",
            "buzz"
        }
    ));
  }
}
