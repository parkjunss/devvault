"use client";

import {
  ArrowsOut, ClosedCaptioning, Pause, Play, SlidersHorizontal, SpeakerHigh, SpeakerSlash
} from "@phosphor-icons/react";
import { useEffect, useRef, useState } from "react";

type VideoPlayerProps = {
  src: string;
  subtitleUrl: string | null;
  subtitleName?: string | null;
  onSubtitleFile: (file: File) => Promise<void>;
  onRemoveSubtitle: () => void;
};

type SubtitleSize = "subtitleSizeSmall" | "subtitleSizeMedium" | "subtitleSizeLarge" | "subtitleSizeExtraLarge";
type SubtitleBackground = "subtitleBgNone" | "subtitleBgSoft" | "subtitleBgDark";

function formatTime(seconds: number) {
  if (!Number.isFinite(seconds)) return "00:00";
  const whole = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  const remaining = whole % 60;
  return hours
    ? `${hours}:${String(minutes).padStart(2, "0")}:${String(remaining).padStart(2, "0")}`
    : `${String(minutes).padStart(2, "0")}:${String(remaining).padStart(2, "0")}`;
}

export function VideoPlayer({
  src,
  subtitleUrl,
  subtitleName,
  onSubtitleFile,
  onRemoveSubtitle
}: VideoPlayerProps) {
  const playerRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const subtitleRef = useRef<HTMLInputElement>(null);
  const [playing, setPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [playbackRate, setPlaybackRate] = useState(1);
  const [subtitleSettingsOpen, setSubtitleSettingsOpen] = useState(false);
  const [subtitleSize, setSubtitleSize] = useState<SubtitleSize>("subtitleSizeLarge");
  const [subtitleBackground, setSubtitleBackground] = useState<SubtitleBackground>("subtitleBgDark");

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    for (const track of Array.from(video.textTracks)) track.mode = "disabled";
    if (subtitleUrl && video.textTracks[0]) video.textTracks[0].mode = "showing";
  }, [subtitleUrl]);

  async function togglePlayback() {
    const video = videoRef.current;
    if (!video) return;
    if (video.paused) {
      await video.play();
    } else {
      video.pause();
    }
  }

  function seek(value: number) {
    const video = videoRef.current;
    if (!video) return;
    video.currentTime = value;
    setCurrentTime(value);
  }

  function changeVolume(value: number) {
    const video = videoRef.current;
    if (!video) return;
    video.volume = value;
    video.muted = value === 0;
    setVolume(value);
    setMuted(value === 0);
  }

  function toggleMute() {
    const video = videoRef.current;
    if (!video) return;
    video.muted = !video.muted;
    setMuted(video.muted);
  }

  function changeRate(value: number) {
    const video = videoRef.current;
    if (!video) return;
    video.playbackRate = value;
    setPlaybackRate(value);
  }

  async function toggleFullscreen() {
    if (!playerRef.current) return;
    if (document.fullscreenElement) {
      await document.exitFullscreen();
    } else {
      await playerRef.current.requestFullscreen();
    }
  }

  return (
    <div className={`videoPlayer ${subtitleSize} ${subtitleBackground}`} ref={playerRef}>
      <video
        ref={videoRef}
        preload="metadata"
        playsInline
        src={src}
        onClick={togglePlayback}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        onTimeUpdate={event => setCurrentTime(event.currentTarget.currentTime)}
        onDurationChange={event => setDuration(event.currentTarget.duration || 0)}
        onVolumeChange={event => {
          setVolume(event.currentTarget.volume);
          setMuted(event.currentTarget.muted);
        }}
      >
        {subtitleUrl && (
          <track
            key={subtitleUrl}
            kind="subtitles"
            src={subtitleUrl}
            srcLang="ko"
            label={subtitleName || "사용자 자막"}
            default
            onLoad={event => {
              event.currentTarget.track.mode = "showing";
            }}
          />
        )}
      </video>
      {subtitleUrl && subtitleSettingsOpen && (
        <div className="subtitleSettings" role="group" aria-label="자막 표시 설정">
          <label>크기
            <select
              value={subtitleSize}
              aria-label="자막 크기"
              onChange={event => setSubtitleSize(event.target.value as SubtitleSize)}
            >
              <option value="subtitleSizeSmall">작게</option>
              <option value="subtitleSizeMedium">보통</option>
              <option value="subtitleSizeLarge">크게</option>
              <option value="subtitleSizeExtraLarge">아주 크게</option>
            </select>
          </label>
          <label>배경
            <select
              value={subtitleBackground}
              aria-label="자막 배경"
              onChange={event => setSubtitleBackground(event.target.value as SubtitleBackground)}
            >
              <option value="subtitleBgNone">없음</option>
              <option value="subtitleBgSoft">반투명</option>
              <option value="subtitleBgDark">진하게</option>
            </select>
          </label>
        </div>
      )}
      <div className="videoControls">
        <input
          className="videoSeek"
          type="range"
          min="0"
          max={duration || 0}
          step="0.05"
          value={Math.min(currentTime, duration || 0)}
          aria-label="재생 위치"
          onChange={event => seek(Number(event.target.value))}
        />
        <div className="videoControlRow">
          <button type="button" onClick={togglePlayback} aria-label={playing ? "일시정지" : "재생"}>
            {playing ? <Pause weight="fill" /> : <Play weight="fill" />}
          </button>
          <span className="videoTime">{formatTime(currentTime)} / {formatTime(duration)}</span>
          <button type="button" onClick={toggleMute} aria-label={muted ? "음소거 해제" : "음소거"}>
            {muted || volume === 0 ? <SpeakerSlash /> : <SpeakerHigh />}
          </button>
          <input
            className="videoVolume"
            type="range"
            min="0"
            max="1"
            step="0.05"
            value={muted ? 0 : volume}
            aria-label="볼륨"
            onChange={event => changeVolume(Number(event.target.value))}
          />
          <select
            value={playbackRate}
            aria-label="재생 속도"
            onChange={event => changeRate(Number(event.target.value))}
          >
            <option value="0.5">0.5×</option>
            <option value="1">1×</option>
            <option value="1.25">1.25×</option>
            <option value="1.5">1.5×</option>
            <option value="2">2×</option>
          </select>
          <input
            ref={subtitleRef}
            type="file"
            accept=".vtt,.srt,.smi,.sami,text/vtt,application/x-subrip"
            hidden
            onChange={async event => {
              const file = event.target.files?.[0];
              if (file) await onSubtitleFile(file);
              event.target.value = "";
            }}
          />
          <button
            type="button"
            className={subtitleUrl ? "active" : ""}
            onClick={() => subtitleRef.current?.click()}
            aria-label={subtitleUrl ? "자막 변경" : "자막 추가"}
            title={subtitleName || "VTT, SRT, SMI 자막 추가"}
          >
            <ClosedCaptioning weight={subtitleUrl ? "fill" : "regular"} />
          </button>
          {subtitleUrl && (
            <button
              type="button"
              className={subtitleSettingsOpen ? "active" : ""}
              aria-label="자막 표시 설정"
              aria-expanded={subtitleSettingsOpen}
              onClick={() => setSubtitleSettingsOpen(open => !open)}
            >
              <SlidersHorizontal />
            </button>
          )}
          {subtitleUrl && <button type="button" className="subtitleRemove" onClick={() => { setSubtitleSettingsOpen(false); onRemoveSubtitle(); }}>자막 끄기</button>}
          <button type="button" onClick={toggleFullscreen} aria-label="전체 화면"><ArrowsOut /></button>
        </div>
      </div>
    </div>
  );
}
