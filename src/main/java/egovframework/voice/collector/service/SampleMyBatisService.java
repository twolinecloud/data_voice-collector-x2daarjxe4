package egovframework.voice.collector.service;

import egovframework.voice.collector.dto.SampleDto;
import egovframework.voice.collector.mapper.SampleMapper;
import egovframework.voice.collector.response.Response;
import egovframework.voice.collector.vo.SampleVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;



@Service
@RequiredArgsConstructor
public class SampleMyBatisService {
    private final SampleMapper sampleMapper;

    
    
    public Response<Object> post(SampleVo vo) {
        SampleDto sampleDto = SampleDto.builder()
                .id(vo.getId())
                .content(vo.getContent())
                .post(vo.getPost())
                .build();

        sampleMapper.insert(sampleDto);

        return Response.of(true);
    }

    
    
    public Response<Object> get() {
        List<SampleDto> selectAll = sampleMapper.selectAll();

        return Response.of(selectAll);
    }

    
    
    public Response<Object> delete(String id) {
        int deletedCount = sampleMapper.delete(id);

        return Response.of(deletedCount);
    }
}