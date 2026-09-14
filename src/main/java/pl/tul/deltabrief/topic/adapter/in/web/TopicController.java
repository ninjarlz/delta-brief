package pl.tul.deltabrief.topic.adapter.in.web;

import jakarta.validation.Valid;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import pl.tul.deltabrief.auth.adapter.out.security.AppUserDetails;
import pl.tul.deltabrief.auth.domain.UserId;
import pl.tul.deltabrief.topic.application.TopicService;
import pl.tul.deltabrief.topic.application.TopicService.CategoryNotFoundException;
import pl.tul.deltabrief.topic.application.TopicService.DuplicateTopicNameException;
import pl.tul.deltabrief.topic.application.TopicService.TopicLimitReachedException;
import pl.tul.deltabrief.topic.application.dto.CreateTopicRequest;
import pl.tul.deltabrief.topic.application.dto.EditScheduleRequest;
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.domain.Category;
import pl.tul.deltabrief.topic.domain.CategoryId;
import pl.tul.deltabrief.topic.domain.Frequency;
import pl.tul.deltabrief.topic.domain.ScheduledRunStatus;
import pl.tul.deltabrief.topic.domain.Topic;
import pl.tul.deltabrief.topic.domain.TopicId;

@Controller
@RequiredArgsConstructor
public class TopicController {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm 'UTC'")
			.withZone(ZoneOffset.UTC);

	private final TopicService topicService;
	private final CategoryRepository categoryRepository;

	/**
	 * Populated in the model for every handler below — used by
	 * {@code topic-form.html}'s category picker, including every error path
	 * of {@link #createTopic}, without repeating this lookup per branch.
	 */
	@ModelAttribute("categories")
	public List<Category> categories() {
		return categoryRepository.findAll();
	}

	/**
	 * Populated in the model for every handler below — used by the frequency
	 * pickers in both {@code topic-form.html} and {@code topic-edit.html},
	 * same pattern as {@link #categories()}.
	 */
	@ModelAttribute("frequencyOptions")
	public List<FrequencyOption> frequencyOptions() {
		return Arrays.stream(Frequency.values()).map(f -> new FrequencyOption(f, frequencyLabel(f))).toList();
	}

	@GetMapping("/")
	public String listTopics(Authentication authentication, Model model,
			@ModelAttribute("categories") List<Category> categories) {
		// Reuses the value the @ModelAttribute("categories") method above already
		// populated for this request — avoids a second categoryRepository.findAll()
		// call that a bare categories() invocation here would otherwise cause.
		Map<Long, String> categoryNamesById = categories.stream()
				.collect(Collectors.toMap(category -> category.id().value(), Category::name));
		List<TopicView> topics = topicService.listTopics(currentUserId(authentication)).stream()
				.map(topic -> toView(topic, categoryNamesById))
				.toList();
		model.addAttribute("topics", topics);
		return "topics";
	}

	@GetMapping("/topics/new")
	public String showCreateForm(Model model) {
		model.addAttribute("createTopicRequest", new CreateTopicRequest());
		return "topic-form";
	}

	@PostMapping("/topics")
	public String createTopic(@Valid @ModelAttribute("createTopicRequest") CreateTopicRequest form,
			BindingResult bindingResult, Authentication authentication) {
		if (bindingResult.hasErrors()) {
			return "topic-form";
		}
		try {
			topicService.createTopic(currentUserId(authentication), form.getName(), new CategoryId(form.getCategoryId()),
					form.getDescription(), form.getFrequency(), form.getPreferredTime(), form.isEmailEnabled());
		} catch (DuplicateTopicNameException alreadyExists) {
			bindingResult.rejectValue("name", "name.duplicate", "You already have a topic with this name");
			return "topic-form";
		} catch (TopicLimitReachedException limitReached) {
			bindingResult.reject("limit.reached", "You've reached the maximum number of topics");
			return "topic-form";
		} catch (CategoryNotFoundException invalidCategory) {
			bindingResult.rejectValue("categoryId", "category.invalid", "Please select a valid category");
			return "topic-form";
		}
		return "redirect:/";
	}

	@GetMapping("/topics/{id}/edit")
	public String showEditForm(@PathVariable Long id, Authentication authentication, Model model) {
		Optional<Topic> topic = topicService.findForSchedule(currentUserId(authentication), new TopicId(id));
		if (topic.isEmpty()) {
			return "redirect:/";
		}
		EditScheduleRequest form = new EditScheduleRequest();
		form.setFrequency(topic.get().frequency());
		form.setPreferredTime(topic.get().preferredTime());
		form.setEmailEnabled(topic.get().emailEnabled());
		model.addAttribute("topicId", id);
		model.addAttribute("editScheduleRequest", form);
		return "topic-edit";
	}

	/**
	 * Redirects to {@code /} both on success and when {@code id} isn't
	 * owned/doesn't exist ({@link TopicService#updateSchedule} silently
	 * no-ops) — same no-information-leak convention as {@link #deleteTopic}.
	 */
	@PostMapping("/topics/{id}/edit")
	public String updateSchedule(@PathVariable Long id,
			@Valid @ModelAttribute("editScheduleRequest") EditScheduleRequest form, BindingResult bindingResult,
			Authentication authentication, Model model) {
		if (bindingResult.hasErrors()) {
			model.addAttribute("topicId", id);
			return "topic-edit";
		}
		topicService.updateSchedule(currentUserId(authentication), new TopicId(id), form.getFrequency(),
				form.getPreferredTime(), form.isEmailEnabled());
		return "redirect:/";
	}

	/**
	 * Always redirects to {@code /} regardless of whether a row was actually
	 * deleted — a request for a topic ID the caller doesn't own behaves
	 * identically to an unknown ID, never distinguishable (see
	 * {@link TopicService#deleteTopic}).
	 */
	@PostMapping("/topics/{id}/delete")
	public String deleteTopic(@PathVariable Long id, Authentication authentication) {
		topicService.deleteTopic(currentUserId(authentication), new TopicId(id));
		return "redirect:/";
	}

	private UserId currentUserId(Authentication authentication) {
		return ((AppUserDetails) authentication.getPrincipal()).userId();
	}

	static TopicView toView(Topic topic, Map<Long, String> categoryNamesById) {
		String categoryName = categoryNamesById.get(topic.categoryId().value());
		String nextDueAt = topic.nextDueAt() == null ? "Not yet scheduled" : TIMESTAMP_FORMAT.format(topic.nextDueAt());
		String nextDueAtIso = topic.nextDueAt() == null ? null : topic.nextDueAt().toString();
		boolean lastRunFailed = topic.lastScheduledStatus() == ScheduledRunStatus.FAILURE;
		return new TopicView(topic.id().value(), topic.name(), categoryName, categoryColorClass(categoryName),
				nextDueAt, nextDueAtIso, lastRunFailed, topic.emailEnabled());
	}

	private static String frequencyLabel(Frequency frequency) {
		return switch (frequency) {
			case DAILY -> "Daily";
			case EVERY_OTHER_DAY -> "Every other day";
			case WEEKLY -> "Weekly";
		};
	}

	/**
	 * Maps a category name to a CSS modifier class (see {@code
	 * .topic-card__category--*} in app.css) so topics are visually
	 * distinguishable by category at a glance. Categories are fixed
	 * reference data seeded by migration (V6), not user-editable, so a
	 * hardcoded mapping is safe — {@code "topic-card__category--default"}
	 * is a defensive fallback for a category added later without a
	 * matching color, not an expected case today.
	 */
	private static String categoryColorClass(String categoryName) {
		if (categoryName == null) {
			return "topic-card__category--default";
		}
		return switch (categoryName) {
			case "World News" -> "topic-card__category--world-news";
			case "Technology" -> "topic-card__category--technology";
			case "Business & Finance" -> "topic-card__category--business-finance";
			case "Science" -> "topic-card__category--science";
			default -> "topic-card__category--default";
		};
	}

	/**
	 * Display-only shape for {@code topics.html} — resolves the category
	 * name once here rather than making the template do a lookup.
	 * {@code nextDueAtIso} carries the raw instant for {@code app.js} to
	 * re-render in the viewer's local timezone. Every {@link Frequency} now
	 * always produces a {@code nextDueAt}, so the null branch (and its
	 * "Not yet scheduled" fallback) is defensive only and should never
	 * trigger in practice.
	 */
	public record TopicView(Long id, String name, String categoryName, String categoryColorClass, String nextDueAt,
			String nextDueAtIso, boolean lastRunFailed, boolean emailEnabled) {
	}

	/**
	 * Display shape for the frequency `<select>` in {@code topic-form.html}
	 * and {@code topic-edit.html} — pairs each {@link Frequency} with a
	 * human-readable label.
	 */
	public record FrequencyOption(Frequency value, String label) {
	}

}
