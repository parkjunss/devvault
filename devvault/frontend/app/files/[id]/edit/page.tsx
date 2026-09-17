import { FileEditor } from "@/components/file-editor";

export default async function EditFilePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <FileEditor fileId={Number(id)} />;
}
