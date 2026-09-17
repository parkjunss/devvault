const { copyFileSync, cpSync, mkdirSync } = require("node:fs");
const { join } = require("node:path");
const root = join(__dirname, "..");
mkdirSync(join(root, "public"), { recursive: true });
copyFileSync(require.resolve("pdfjs-dist/build/pdf.worker.min.mjs"), join(root, "public/pdf.worker.min.mjs"));
for (const directory of ["cmaps", "standard_fonts", "wasm"]) {
  cpSync(join(root, "node_modules/pdfjs-dist", directory), join(root, "public/pdf-assets", directory), { recursive: true });
}
