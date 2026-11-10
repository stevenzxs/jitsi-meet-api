package com.jitsimeet.api.controller;

import com.jitsimeet.api.service.BannerLogoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/open/desktop/meetings/{roomName}/banner/logos")
public class BannerLogoController {
    private final BannerLogoService logos;

    public BannerLogoController(BannerLogoService logos) { this.logos = logos; }

    @PutMapping("/{scope}/{fileName}")
    public ResponseEntity<Void> put(@PathVariable String roomName, @PathVariable String scope,
                                    @PathVariable String fileName,
                                    @RequestHeader(value = "X-Host-Key", required = false) String hostKey,
                                    @RequestHeader(value = "Content-Type", required = false) String contentType,
                                    @RequestBody byte[] body) {
        logos.put(roomName, scope, fileName, hostKey, body, contentType);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{scope}/{fileName}")
    public ResponseEntity<Void> delete(@PathVariable String roomName, @PathVariable String scope,
                                       @PathVariable String fileName,
                                       @RequestHeader(value = "X-Host-Key", required = false) String hostKey) {
        logos.delete(roomName, scope, fileName, hostKey);
        return ResponseEntity.noContent().build();
    }
}
