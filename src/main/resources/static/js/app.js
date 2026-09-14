// Disables a form's submit button the moment it's submitted, so an
// impatient double-click can't fire a second overlapping request (which,
// depending on server-side session/state handling, can surface as a
// confusing error on the second request instead of just being a no-op).
// The browser has already captured the form data by the time this handler
// runs, so disabling the button here doesn't affect what gets submitted.
// Pico.css greys out any [disabled] button on its own (opacity + not-allowed
// cursor); a button opting in via data-busy-text (slow, synchronous
// server-side work — e.g. briefing generation) also gets aria-busy="true",
// which Pico renders as a spinning circle icon, plus its label text swapped
// to that busy text, so there's a visible indicator while the request is in
// flight instead of the page just sitting there looking frozen.
document.addEventListener('submit', function (event) {
	var submitButton = event.target.querySelector('button[type="submit"]');
	if (submitButton && !submitButton.disabled) {
		submitButton.disabled = true;
		var busyText = submitButton.getAttribute('data-busy-text');
		if (busyText) {
			submitButton.setAttribute('aria-busy', 'true');
			submitButton.textContent = busyText;
			// A busy button means a slow, synchronous full-page action (e.g.
			// briefing generation) — submitting one of these is a real page
			// navigation, so starting a second one before the first
			// responds would abandon this request's response entirely and
			// leave the user on whichever one they clicked last, per the
			// browser's standard "a new navigation cancels the pending
			// one" behavior. Grey out every other busy-capable button on
			// the page (e.g. "Generate briefing" for other topics) so that
			// can't happen — only the clicked one gets the busy animation.
			document.querySelectorAll('[data-busy-text]').forEach(function (other) {
				if (other !== submitButton) {
					other.disabled = true;
				}
			});
		}
	}
}, true);

// The server renders timestamps in UTC (it has no way to know the viewer's
// timezone) into a data-timestamp attribute alongside a UTC fallback text.
// This script runs on the browser, so it can convert to the viewer's actual
// local timezone; if JS is disabled, the UTC fallback text stays as-is.
document.querySelectorAll('[data-timestamp]').forEach(function (el) {
	var date = new Date(el.getAttribute('data-timestamp'));
	if (!isNaN(date.getTime())) {
		el.textContent = date.toLocaleString(undefined, {dateStyle: 'medium', timeStyle: 'short'});
	}
});

// Preferred-time picker on topic-form.html/topic-edit.html: the app has no
// per-user timezone concept, so the server only ever stores/reads UTC. The
// visible <input type="time"> lets the user think in their own local time;
// a hidden field alongside it carries the actual UTC value Spring binds to
// preferredTime. "Today" is used as the reference date for the UTC<->local
// conversion (there's no date, only a time-of-day) — a DST-transition edge
// case could shift the displayed local hour by one on the transition day
// itself, an accepted tradeoff for a "nudge, not a guarantee" schedule.
function pad2(n) {
	return String(n).padStart(2, '0');
}

function utcTimeToLocal(utcTime) {
	var parts = utcTime.split(':');
	var d = new Date();
	d.setUTCHours(Number(parts[0]), Number(parts[1]), 0, 0);
	return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
}

function localTimeToUtc(localTime) {
	var parts = localTime.split(':');
	var d = new Date();
	d.setHours(Number(parts[0]), Number(parts[1]), 0, 0);
	return pad2(d.getUTCHours()) + ':' + pad2(d.getUTCMinutes());
}

(function () {
	var hidden = document.querySelector('[data-preferred-time-utc]');
	var visible = document.querySelector('[data-preferred-time-local]');
	var help = document.querySelector('[data-preferred-time-help]');
	if (!hidden || !visible) {
		return;
	}
	if (hidden.value) {
		visible.value = utcTimeToLocal(hidden.value);
	}
	if (help) {
		help.textContent = 'DeltaBrief nudges automatic generation toward this time when set. Leave blank to use '
			+ 'the default — 9:00 UTC (' + utcTimeToLocal('09:00') + ' your time).';
	}
	visible.addEventListener('change', function () {
		hidden.value = visible.value ? localTimeToUtc(visible.value) : '';
	});
})();
