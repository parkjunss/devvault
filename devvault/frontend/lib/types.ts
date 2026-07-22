export type Tag = { id: number; name: string };

export type VaultFile = {
  id: number;
  originalName: string;
  folderId: number | null;
  contentType: string | null;
  size: number;
  checksum: string;
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
