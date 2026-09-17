export type Tag = { id: number; name: string };

export type VaultFile = {
  id: number;
  originalName: string;
  folderId: number | null;
  contentType: string | null;
  size: number;
  checksum: string;
  version?: number;
  favorite: boolean;
  createdAt: string;
  tags?: Tag[];
};

export type Folder = {
  id: number;
  name: string;
  parentId: number | null;
  createdAt: string;
};

export type PageResponse<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
};

export type AuthToken = {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
};

export type CourseStatus = "DRAFT" | "ACTIVE" | "ARCHIVED";

export type Course = {
  id: number;
  title: string;
  description: string | null;
  status: CourseStatus;
  createdAt: string;
};

export type Section = {
  id: number;
  title: string;
  orderNo: number;
  createdAt: string;
};

export type LessonSourceType = "UPLOAD" | "WRITTEN";

export type Lesson = {
  id: number;
  title: string;
  orderNo: number;
  contentMd: string;
  sourceType: LessonSourceType;
  sourceFileId: number | null;
  createdAt: string;
};

export type FileVersion = { version: number; originalName: string; contentType: string; size: number; checksum: string; createdAt: string; current: boolean };
