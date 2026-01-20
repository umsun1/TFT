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
							  Model model){
		SummonerProfileDto profile = summonerService.getSummonerData(server, gameName, tagLine, queueId);
		
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
	}
	
}