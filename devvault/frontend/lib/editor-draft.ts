import type { Mark } from "./document-editing";

export type EditorDraft = { marks: Mark[]; image: string | null; updatedAt: number };

// Store only after the server has authorized access to the matching file and checksum.
export async function editorDraft(key: string, action: "read" | "write" | "delete", value?: EditorDraft): Promise<EditorDraft | undefined> {
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open("devvault-editor", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("drafts");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  try {
    return await new Promise((resolve, reject) => {
      const transaction = db.transaction("drafts", action === "read" ? "readonly" : "readwrite");
      const store = transaction.objectStore("drafts");
      const request = action === "read" ? store.get(key) : action === "write" ? store.put(value, key) : store.delete(key);
      transaction.oncomplete = () => resolve(action === "read" ? request.result : undefined);
      transaction.onabort = () => reject(transaction.error);
      transaction.onerror = () => reject(transaction.error);
    });
  } finally { db.close(); }
}
