"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { CaretLeft, FileText } from "@phosphor-icons/react";
import { useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import { apiJson } from "@/lib/api";
import { getAccessToken } from "@/lib/auth";
import type { Lesson } from "@/lib/types";

export function LessonViewer() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const lessonId = Number(params.id);
  const [lesson, setLesson] = useState<Lesson | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!getAccessToken()) {
      router.replace("/login");
      return;
    }
    apiJson<Lesson>(`/api/lessons/${lessonId}`)
      .then(setLesson)
      .catch(exception => setError(exception instanceof Error ? exception.message : "교재를 불러오지 못했습니다."));
  }, [lessonId, router]);

  return (
    <div className="learningShell">
      <header className="learningHeader">
        <Link className="brand" href="/courses"><CaretLeft /> 강의 목록</Link>
        <h1><FileText /> {lesson?.title ?? "교재"}</h1>
      </header>

      <main className="learningContent">
        {error && <p className="learningError">{error}</p>}
        {!lesson && !error && <p className="stateMessage">불러오는 중...</p>}
        {lesson && (
          <article className="lessonViewer">
            <ReactMarkdown remarkPlugins={[remarkMath]} rehypePlugins={[rehypeKatex]}>
              {lesson.contentMd}
            </ReactMarkdown>
          </article>
        )}
      </main>
    </div>
  );
}
