"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { BookOpen, CaretLeft, FileText, Plus, UploadSimple } from "@phosphor-icons/react";
import { ChangeEvent, FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { apiJson, apiUpload } from "@/lib/api";
import { getAccessToken } from "@/lib/auth";
import type { Course, Lesson, Section } from "@/lib/types";

type SectionWithLessons = Section & { lessons: Lesson[] };
type UploadState = { sectionId: number; fileName: string; loaded: number; total: number } | null;

export function CourseDetail() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const courseId = Number(params.id);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const uploadTargetSectionId = useRef<number | null>(null);

  const [course, setCourse] = useState<Course | null>(null);
  const [sections, setSections] = useState<SectionWithLessons[] | null>(null);
  const [error, setError] = useState("");
  const [newSectionTitle, setNewSectionTitle] = useState("");
  const [creatingSection, setCreatingSection] = useState(false);
  const [upload, setUpload] = useState<UploadState>(null);
  const [writeForm, setWriteForm] = useState<{ sectionId: number; title: string; contentMd: string } | null>(null);

  const load = useCallback(async () => {
    try {
      const [courseResult, sectionList] = await Promise.all([
        apiJson<Course>(`/api/courses/${courseId}`),
        apiJson<Section[]>(`/api/courses/${courseId}/sections`)
      ]);
      setCourse(courseResult);
      const withLessons = await Promise.all(
        sectionList.map(async section => ({
          ...section,
          lessons: await apiJson<Lesson[]>(`/api/sections/${section.id}/lessons`)
        }))
      );
      setSections(withLessons);
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "강의를 불러오지 못했습니다.");
    }
  }, [courseId]);

  useEffect(() => {
    if (!getAccessToken()) {
      router.replace("/login");
      return;
    }
    const timer = window.setTimeout(load, 0);
    return () => window.clearTimeout(timer);
  }, [load, router]);

  async function createSection(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCreatingSection(true);
    setError("");
    try {
      const orderNo = (sections?.length ?? 0) + 1;
      const section = await apiJson<Section>(`/api/courses/${courseId}/sections`, {
        method: "POST",
        body: JSON.stringify({ title: newSectionTitle, orderNo })
      });
      setSections(current => [...(current ?? []), { ...section, lessons: [] }]);
      setNewSectionTitle("");
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "주차를 만들지 못했습니다.");
    } finally {
      setCreatingSection(false);
    }
  }

  function pickFileFor(sectionId: number) {
    uploadTargetSectionId.current = sectionId;
    fileInputRef.current?.click();
  }

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    const sectionId = uploadTargetSectionId.current;
    event.target.value = "";
    if (!file || sectionId == null) return;
    setError("");
    setUpload({ sectionId, fileName: file.name, loaded: 0, total: file.size });
    try {
      const body = new FormData();
      body.append("file", file);
      const uploadResponse = await apiUpload("/api/files", body, (loaded, total) =>
        setUpload({ sectionId, fileName: file.name, loaded, total: total || file.size }));
      if (!uploadResponse.ok) throw new Error("파일 업로드에 실패했습니다.");
      const storedFile = await uploadResponse.json() as { id: number };
      const section = sections?.find(candidate => candidate.id === sectionId);
      const orderNo = (section?.lessons.length ?? 0) + 1;
      const lesson = await apiJson<Lesson>(`/api/sections/${sectionId}/lessons/from-file`, {
        method: "POST",
        body: JSON.stringify({ storedFileId: storedFile.id, title: file.name, orderNo })
      });
      setSections(current => current?.map(candidate =>
        candidate.id === sectionId ? { ...candidate, lessons: [...candidate.lessons, lesson] } : candidate) ?? null);
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "자료를 교재로 만들지 못했습니다.");
    } finally {
      setUpload(null);
    }
  }

  async function submitWrittenLesson(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!writeForm) return;
    setError("");
    try {
      const section = sections?.find(candidate => candidate.id === writeForm.sectionId);
      const orderNo = (section?.lessons.length ?? 0) + 1;
      const lesson = await apiJson<Lesson>(`/api/sections/${writeForm.sectionId}/lessons`, {
        method: "POST",
        body: JSON.stringify({ title: writeForm.title, contentMd: writeForm.contentMd, orderNo })
      });
      setSections(current => current?.map(candidate =>
        candidate.id === writeForm.sectionId ? { ...candidate, lessons: [...candidate.lessons, lesson] } : candidate) ?? null);
      setWriteForm(null);
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "교재를 만들지 못했습니다.");
    }
  }

  return (
    <div className="learningShell">
      <header className="learningHeader">
        <Link className="brand" href="/courses"><CaretLeft /> 강의 목록</Link>
        <h1><BookOpen /> {course?.title ?? "강의"}</h1>
      </header>

      <main className="learningContent">
        {error && <p className="learningError">{error}</p>}
        {course?.description && <p className="courseDescription">{course.description}</p>}

        <input ref={fileInputRef} className="visuallyHidden" type="file"
               accept=".pdf,.docx,.md,.txt" onChange={handleFileChange} />

        <form className="sectionCreateForm" onSubmit={createSection}>
          <input
            placeholder="새 주차 제목 (예: 1주차 - 역전파)"
            value={newSectionTitle}
            onChange={event => setNewSectionTitle(event.target.value)}
            required
            maxLength={200}
          />
          <button className="secondaryButton" type="submit" disabled={creatingSection || !newSectionTitle.trim()}>
            <Plus /> 주차 추가
          </button>
        </form>

        {sections === null && !error && <p className="stateMessage">불러오는 중...</p>}

        {sections?.map(section => (
          <section key={section.id} className="sectionBlock">
            <div className="sectionBlockHeader">
              <h2>{section.title}</h2>
              <div className="sectionBlockActions">
                <button className="secondaryButton" type="button" onClick={() => pickFileFor(section.id)}
                        disabled={upload !== null}>
                  <UploadSimple /> 자료 업로드
                </button>
                <button className="secondaryButton" type="button"
                        onClick={() => setWriteForm({ sectionId: section.id, title: "", contentMd: "" })}>
                  <FileText /> 직접 작성
                </button>
              </div>
            </div>

            {upload?.sectionId === section.id && (
              <div className="uploadProgress">
                <div><strong>{upload.fileName}</strong><span>{Math.round((upload.loaded / (upload.total || 1)) * 100)}%</span></div>
                <div className="uploadProgressTrack"><span style={{ width: `${Math.round((upload.loaded / (upload.total || 1)) * 100)}%` }} /></div>
              </div>
            )}

            {writeForm?.sectionId === section.id && (
              <form className="writtenLessonForm" onSubmit={submitWrittenLesson}>
                <input
                  placeholder="교재 제목"
                  value={writeForm.title}
                  onChange={event => setWriteForm({ ...writeForm, title: event.target.value })}
                  required
                  maxLength={200}
                />
                <textarea
                  placeholder="마크다운으로 작성하세요..."
                  value={writeForm.contentMd}
                  onChange={event => setWriteForm({ ...writeForm, contentMd: event.target.value })}
                  required
                  rows={6}
                />
                <div className="writtenLessonFormActions">
                  <button className="secondaryButton" type="button" onClick={() => setWriteForm(null)}>취소</button>
                  <button className="primaryButton" type="submit" disabled={!writeForm.title.trim() || !writeForm.contentMd.trim()}>저장</button>
                </div>
              </form>
            )}

            {section.lessons.length === 0
              ? <p className="lessonListEmpty">아직 교재가 없어요.</p>
              : (
                <ul className="lessonList">
                  {section.lessons.map(lesson => (
                    <li key={lesson.id}>
                      <Link href={`/lessons/${lesson.id}`}><FileText /> {lesson.title}</Link>
                    </li>
                  ))}
                </ul>
              )}
          </section>
        ))}
      </main>
    </div>
  );
}
