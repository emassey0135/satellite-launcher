// Orbit step aside, a Backtalk script for the Orbit Speak and Orbit Player.
//
// Orbit's own apps (com.orbit.*), and apps that declare that they speak for themselves (the orbit.self_voicing
// manifest flag), speak through Orbit's screen reader, so Backtalk stays quiet in them instead of talking over
// them. Notifications are still read while one of them is in front. Satellite's setup installs this.

export const manifest = {
  id: 'orbit-step-aside',
  name: 'Orbit step aside',
  version: '1.1',
  author: 'Elijah Massey',
  description:
    "Keeps Backtalk quiet in Orbit's own apps and in apps that speak for themselves through Orbit's screen " +
    "reader, so the two don't speak over each other. Notifications are still read.",
  minApiVersion: 1,
  testedApiVersion: 3,
  // Runs in every app; 'screen' lets it see which app is in front, 'speech' lets it drop speech, and
  // 'notifications' lets it still read notifications aloud while an app is otherwise silenced.
  permissions: ['screen', 'speech', 'notifications'],
};

// Stay silent for Orbit's own apps (com.orbit.*) and for any app that declares it speaks for itself
// (the orbit.self_voicing manifest flag, read from backtalk.app.meta). On an older Backtalk without
// app.meta, the flag is simply undefined and only the com.orbit.* rule applies.
function selfVoicingInFront() {
  const app = backtalk.app;
  if (!app) return false;
  if (typeof app.package === 'string' && app.package.startsWith('com.orbit.')) return true;
  const meta = app.meta;
  return !!(meta && meta['orbit.self_voicing']);
}

// Notifications should still be heard even while a self-voicing app is in front. The onSpeech filter below would
// otherwise silence them, so re-speak them ourselves: script speech is marked fromScript and bypasses that filter.
backtalk.onNotification((info, text) => {
  if (!selfVoicingInFront()) return undefined; // normal apps: Backtalk announces notifications as usual
  if (text) backtalk.speak(text); // self-voicing app: say it ourselves (not silenced by onSpeech)
  return false; // suppress Backtalk's own announcement (onSpeech would drop it anyway)
});

// Before Backtalk says anything, say nothing while a self-voicing app is in front; otherwise leave it alone.
// (Notifications are handled above and are not affected by this, since script speech bypasses this filter.)
backtalk.onSpeech(() => (selfVoicingInFront() ? false : undefined));
