package pl.tul.deltabrief.topic.adapter.in.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
import pl.tul.deltabrief.topic.application.port.out.CategoryRepository;
import pl.tul.deltabrief.topic.domain.Category;
import pl.tul.deltabrief.topic.domain.CategoryId;
import pl.tul.deltabrief.topic.domain.Topic;
import pl.tul.deltabrief.topic.domain.TopicId;

@Controller
public class TopicController {

	private final TopicService topicService;
	private final CategoryRepository categoryRepository;

	public TopicController(TopicService topicService, CategoryRepository categoryRepository) {
		this.topicService = topicService;
		this.categoryRepository = categoryRepository;
	}

	/**
	 * Populated in the model for every handler below — used by
	 * {@code topic-form.html}'s category picker, including every error path
	 * of {@link #createTopic}, without repeating this lookup per branch.
	 */
	@ModelAttribute("categories")
	public List<Category> categories() {
		return categoryRepository.findAll();
	}

	@GetMapping("/")
	public String listTopics(Authentication authentication, Model model) {
		Map<Long, String> categoryNamesById = categories().stream()
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
			topicService.createTopic(currentUserId(authentication), form.getName(), new CategoryId(form.getCategoryId()));
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

	private static TopicView toView(Topic topic, Map<Long, String> categoryNamesById) {
		String categoryName = categoryNamesById.get(topic.categoryId().value());
		return new TopicView(topic.id().value(), topic.name(), categoryName);
	}

	/**
	 * Display-only shape for {@code topics.html} — resolves the category
	 * name once here rather than making the template do a lookup.
	 */
	public record TopicView(Long id, String name, String categoryName) {
	}

}
