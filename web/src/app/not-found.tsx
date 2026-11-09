import Link from "next/link";

export default function NotFound() {
  return (
    <main style={{ display: "grid", gap: "1rem", justifyItems: "start", maxWidth: "36rem", padding: "4rem clamp(1.25rem, 5vw, 4rem)" }}>
      <h1>Nothing here</h1>
      <p className="muted">This project or page doesn&rsquo;t exist, or it belongs to another account.</p>
      <Link href="/projects" className="btn btn-quiet">
        Go to your projects
      </Link>
    </main>
  );
}
