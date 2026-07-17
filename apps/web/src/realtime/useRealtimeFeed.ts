import { useEffect, useRef, useState } from "react";
import { getRealtimeConfig, openRealtimeSession } from "../lib/api";

type RealtimeEvent = {
  type: string;
  classroomCode: string;
  occurredAt: string;
  payload: Record<string, unknown>;
};

export function useRealtimeFeed(token: string | null, classroomCode: string | null) {
  const [events, setEvents] = useState<RealtimeEvent[]>([]);
  const [status, setStatus] = useState<"idle" | "connecting" | "connected" | "error">("idle");
  const socketRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    if (!token || !classroomCode) {
      return;
    }

    let cancelled = false;

    async function connect() {
      try {
        setStatus("connecting");
        if (!token || !classroomCode) {
          return;
        }
        const activeToken: string = token;
        const activeClassroomCode: string = classroomCode;
        const config = await getRealtimeConfig(activeToken);
        const session = await openRealtimeSession(activeToken, activeClassroomCode);
        if (cancelled) {
          return;
        }

        const socketUrl = new URL(config.url);
        socketUrl.searchParams.set("token", activeToken);
        socketUrl.searchParams.set("classroomCode", activeClassroomCode);
        socketUrl.searchParams.set("sessionId", session.sessionId);

        const socket = new WebSocket(socketUrl.toString());
        socketRef.current = socket;
        socket.onopen = () => setStatus("connected");
        socket.onerror = () => setStatus("error");
        socket.onclose = () => setStatus("error");
        socket.onmessage = (message) => {
          const parsed = JSON.parse(message.data) as RealtimeEvent;
          setEvents((current) => [...current, parsed]);
        };
      } catch {
        setStatus("error");
      }
    }

    void connect();
    return () => {
      cancelled = true;
      socketRef.current?.close();
    };
  }, [token, classroomCode]);

  return {
    events,
    status
  };
}
