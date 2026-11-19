import { afterEach, describe, expect, it } from "vitest";
import { PlayerHead, renderPlayerHead } from "./player-head";

afterEach(() => {
  document.body.innerHTML = "";
});

describe("PlayerHead", () => {
  it("renders the default size and avatar url", () => {
    const html = renderPlayerHead({ name: "nightware6" });

    expect(html).toContain("player-head--md");
    expect(html).toContain("mc-heads.net/avatar/nightware6");
    expect(html).toContain('alt="nightware6"');
  });

  it("uses an explicit avatar and online state", () => {
    const html = renderPlayerHead({ name: "Dev", avatar: "/avatar.png", online: true, size: "lg", statusColor: "#4f8f4c" });

    expect(html).toContain("player-head--lg");
    expect(html).toContain("player-head--online");
    expect(html).toContain('src="/avatar.png"');
    expect(html).toContain("--player-head-status-color: #4f8f4c");
  });

  it("renders the same avatar for online and offline states and only changes the status indicator", () => {
    const online = renderPlayerHead({ name: "Dev", avatar: "/avatar.png", online: true, statusColor: "#4f8f4c" });
    const offline = renderPlayerHead({ name: "Dev", avatar: "/avatar.png", online: false, statusColor: "#4f8f4c", statusFill: "black" });

    expect(online).toContain('src="/avatar.png"');
    expect(offline).toContain('src="/avatar.png"');
    expect(online).toContain("player-head__status");
    expect(offline).toContain("player-head__status");
    expect(online).toContain("player-head--online");
    expect(offline).toContain("player-head--offline");
    expect(offline).toContain("--player-head-status-fill: black");
  });
});
