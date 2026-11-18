import Link from "next/link";

export default function NotFound() {
  return (
    <main style={{ display: "grid", gap: "1rem", justifyItems: "start", maxWidth: "36rem", padding: "4rem clamp(1.25rem, 5vw, 4rem)" }}>
      <h1>Nothing here</h1>
      <p className="muted">This page doesn&rsquo;t exist, the profile isn&rsquo;t public, or it belongs to another account.</p>
      <Link href="/" className="btn btn-quiet">
        Go to the home page
      </Link>
    </main>
  );
}
