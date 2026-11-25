"""Screenshots of the web app running against the real API, for the demonstration evidence.

Needs the API on :8090 (local profile, demo pool seeded) and the web app on :3100.
usage: python docs/submission/capture_screens.py
"""
import uuid
from pathlib import Path

from playwright.sync_api import sync_playwright

B = "http://localhost:3100"
OUT = Path(__file__).parent / "assets"
OUT.mkdir(exist_ok=True)
JD = ("Must have strong SQL and Python.\nDashboards in Power BI.\nStatistics is a plus.")


def shot(page, name, full=False):
    page.wait_for_timeout(900)
    page.screenshot(path=str(OUT / f"{name}.png"), full_page=full)
    print("saved", name)


with sync_playwright() as p:
    br = p.chromium.launch()
    ctx = br.new_context(viewport={"width": 1280, "height": 800})
    ctx.set_default_timeout(60000)
    page = ctx.new_page()

    page.goto(B + "/sign-in", wait_until="networkidle")
    guest = page.get_by_text("Continue with a guest account")
    if guest.count():
        guest.click()
    page.fill("#handle", "recruiter-" + uuid.uuid4().hex[:5])
    page.get_by_role("button", name="Continue").click()
    page.wait_for_url("**/start**")

    # Error: a drive without a job description.
    page.goto(B + "/hire", wait_until="networkidle")
    page.locator("input[placeholder='Data analyst intern']").fill("Data analyst")
    page.get_by_role("button", name="Create drive").click()
    alert = page.locator("p[role=alert]")
    alert.wait_for()
    alert.scroll_into_view_if_needed()
    page.evaluate("window.scrollBy(0, 120)")
    shot(page, "ui-error-empty-description")

    # Real drive over the 300-candidate pool: shortlist, then assignment.
    page.locator("textarea").first.fill(JD)
    page.get_by_role("button", name="Create drive").click()
    page.wait_for_url("**/hire/*")
    page.wait_for_load_state("networkidle")
    shot(page, "real-drive-shortlist")

    # Error: an impossible minimum match is refused before saving.
    page.get_by_label("Minimum match").fill("150")
    page.get_by_role("button", name="Save changes").click()
    page.wait_for_timeout(1200)
    field = page.get_by_label("Minimum match")
    field.scroll_into_view_if_needed()
    page.evaluate("window.scrollBy(0, 160)")
    shot(page, "ui-error-threshold")
    page.get_by_label("Minimum match").fill("50")
    page.get_by_label("Seats").fill("10")
    page.get_by_role("button", name="Save changes").click()
    page.wait_for_selector("button:has-text('Saved')")
    page.get_by_role("button", name="Assign all").click()
    page.wait_for_url("**/assign**")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(2500)  # let the count-up numbers and bars finish
    shot(page, "real-assignment", full=True)

    # Error: a profile that does not exist.
    page.goto(B + "/u/no-such-person-xyz", wait_until="networkidle")
    page.wait_for_timeout(900)
    page.screenshot(path=str(OUT / "ui-error-not-found.png"), clip={"x": 0, "y": 0, "width": 1280, "height": 380})
    br.close()
