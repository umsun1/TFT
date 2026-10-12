package com.tft.web.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.tft.web.domain.Participant;
import com.tft.web.model.dto.MatchApiDto;
import com.tft.web.model.dto.ParticipantDto;
import com.tft.web.model.dto.SummonerProfileDto;
import com.tft.web.model.dto.UnitDto;
import com.tft.web.service.MatchService;
import com.tft.web.service.SummonerService;

@Controller
public class MainController {
	
	@Autowired
	SummonerService summonerService;

	@Autowired
	MatchService matchService;

	@Autowired
	private com.tft.web.repository.ParticipantRepository participantRepository;

	@GetMapping("/api/summoner/search-tag/{name}")
	@org.springframework.web.bind.annotation.ResponseBody
	public String searchTag(@PathVariable String name) {
		List<com.tft.web.domain.Participant> participants = participantRepository.findByPaName(name);
		if (participants != null && !participants.isEmpty()) {
			// 가장 최근 전적이 있는 유저의 태그를 반환
			return participants.get(0).getPaTag();
		}
		return "KR1"; // 못 찾으면 기본값
	}

	@GetMapping("/")
	public String main(Model model) {
        model.addAttribute("name", "TFT Player");
		return "tft/index";
	}

    @GetMapping("/summoner/{server}/{gameName}/{tagLine}")
	public String getSummoner(@PathVariable String server, @PathVariable String gameName, @PathVariable String tagLine, 
							  @RequestParam(defaultValue = "0") int page,
							  @RequestParam(defaultValue = "1100") int queueId,
							  Model model,
							  org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes){
		try {
			SummonerProfileDto profile = summonerService.getSummonerData(server, gameName, tagLine, queueId);
			if (profile == null) {
				redirectAttributes.addFlashAttribute("errorMessage", "소환사를 찾을 수 없습니다.");
				return "redirect:/";
			}
			
			String puuid = profile.getPuuid();
			
			// 페이징 서비스 호출 (DB 기반 조회 및 초기 수집 포함)
			Page<MatchApiDto> matchPage = matchService.getRecentMatches(puuid, page, queueId);
			
			model.addAttribute("matches", matchPage.getContent());
			model.addAttribute("profile", profile);
			model.addAttribute("server", server);
			model.addAttribute("currentPage", page);
			model.addAttribute("currentQueueId", queueId);
			model.addAttribute("hasNext", matchPage.hasNext());
			model.addAttribute("totalMatches", matchPage.getTotalElements());

			return "tft/summoner";
		} catch (com.tft.web.ratelimit.RiotRateLimitedException e) {
			// 호출 전에 막힌 경우: Riot 에 요청하지 않았다. 남은 시간을 안내한다.
			String message = e.isLimiterUnavailable()
					? "일시적인 문제로 소환사 정보를 조회할 수 없습니다. 잠시 후 다시 검색해주세요."
					: "현재 요청이 많아 소환사 정보를 조회할 수 없습니다. 약 " + e.getRetryAfterSeconds() + "초 후 다시 검색해주세요.";
			redirectAttributes.addFlashAttribute("errorMessage", message);
			return "redirect:/";
		} catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
			redirectAttributes.addFlashAttribute("errorMessage", "소환사를 찾을 수 없습니다.");
			return "redirect:/";
		} catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
			redirectAttributes.addFlashAttribute("errorMessage", "Riot API 요청 한도가(Rate Limit) 일시적으로 초과되었습니다. 잠시 후 다시 검색해주세요.");
			return "redirect:/";
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("errorMessage", "검색 중 알 수 없는 오류가 발생했습니다: " + e.getMessage());
			return "redirect:/";
		}
	}

	// 전적 카드 상세(1~8등) - 카드 클릭 시 fetch로 호출되어 HTML fragment만 반환
	@GetMapping("/match/{matchId}/detail")
	public String getMatchDetail(@PathVariable String matchId,
								 @RequestParam(defaultValue = "") String puuid,
								 @RequestParam(defaultValue = "kr") String server,
								 Model model) {
		MatchApiDto match;
		try {
			match = matchService.getMatchDetailById(matchId, puuid);
		} catch (com.tft.web.ratelimit.RiotRateLimitedException e) {
			throw new org.springframework.web.server.ResponseStatusException(
					org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "요청이 많아 매치 정보를 불러올 수 없습니다.");
		}
		if (match == null) {
			throw new org.springframework.web.server.ResponseStatusException(
					org.springframework.http.HttpStatus.NOT_FOUND, "매치 정보를 찾을 수 없습니다: " + matchId);
		}
		model.addAttribute("match", match);
		model.addAttribute("myPuuid", puuid);
		model.addAttribute("server", server);
		return "fragments/match-detail :: detail";
	}
}