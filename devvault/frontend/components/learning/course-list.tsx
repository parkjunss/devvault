"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { BookOpen, FolderOpen, Plus } from "@phosphor-icons/react";
import { FormEvent, useEffect, useState } from "react";
import { apiJson } from "@/lib/api";
import { getAccessToken } from "@/lib/auth";
import type { Course } from "@/lib/types";

export function CourseList() {
  const router = useRouter();
  const [courses, setCourses] = useState<Course[] | null>(null);
  const [error, setError] = useState("");
  const [creating, setCreating] = useState(false);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");

  useEffect(() => {
    if (!getAccessToken()) {
      router.replace("/login");
      return;
    }
    apiJson<Course[]>("/api/courses")
      .then(setCourses)
      .catch(exception => setError(exception instanceof Error ? exception.message : "강의를 불러오지 못했습니다."));
  }, [router]);

  async function createCourse(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCreating(true);
    setError("");
    try {
      const course = await apiJson<Course>("/api/courses", {
        method: "POST",
        body: JSON.stringify({ title, description: description || null })
      });
      setCourses(current => [course, ...(current ?? [])]);
      setTitle("");
      setDescription("");
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "강의를 만들지 못했습니다.");
    } finally {
      setCreating(false);
    }
  }

  return (
    <div className="learningShell">
      <header className="learningHeader">
        <Link className="brand" href="/files"><FolderOpen /><strong>DevVault</strong></Link>
        <h1><BookOpen /> 학습</h1>
      </header>

      <main className="learningContent">
        <form className="courseCreateForm" onSubmit={createCourse}>
          <input
            placeholder="새 강의 제목"
            value={title}
            onChange={event => setTitle(event.target.value)}
            required
            maxLength={200}
          />
          <input
            placeholder="설명 (선택)"
            value={description}
            onChange={event => setDescription(event.target.value)}
            maxLength={2000}
          />
          <button className="primaryButton" type="submit" disabled={creating || !title.trim()}>
            <Plus /> 강의 만들기
          </button>
        </form>

        {error && <p className="learningError">{error}</p>}

        {courses === null && !error && <p className="stateMessage">불러오는 중...</p>}

        {courses !== null && courses.length === 0 && (
          <div className="stateMessage">
            <BookOpen />
            <strong>아직 강의가 없어요</strong>
            <span>위에서 첫 강의를 만들어 보세요.</span>
          </div>
        )}

        {courses !== null && courses.length > 0 && (
          <div className="courseGrid">
            {courses.map(course => (
              <Link key={course.id} href={`/courses/${course.id}`} className="courseCard">
                <strong>{course.title}</strong>
                {course.description && <p>{course.description}</p>}
                <span className={`courseStatus courseStatus-${course.status.toLowerCase()}`}>{course.status}</span>
              </Link>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}
