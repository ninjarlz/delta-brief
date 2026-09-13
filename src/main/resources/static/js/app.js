// Disables a form's submit button the moment it's submitted, so an
// impatient double-click can't fire a second overlapping request (which,
// depending on server-side session/state handling, can surface as a
// confusing error on the second request instead of just being a no-op).
// The browser has already captured the form data by the time this handler
// runs, so disabling the button here doesn't affect what gets submitted.
document.addEventListener('submit', function (event) {
	var submitButton = event.target.querySelector('button[type="submit"]');
	if (submitButton && !submitButton.disabled) {
		submitButton.disabled = true;
	}
}, true);
